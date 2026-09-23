package snd.komelia.image

import androidx.compose.ui.unit.IntSize
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private val logger = KotlinLogging.logger {}

internal enum class ImageUpdatePriority {
    /** viewport or transition work a caller is waiting on; always selected first */
    DEMAND,

    /** best-effort neighbour coverage; yields to demand at every tile boundary */
    PREFETCH,
}

/**
 * Identity of a prefetch pass. Two submissions with the same key describe the same coverage,
 * so the second one coalesces onto the first instead of rendering it again.
 */
internal data class PrefetchKey(
    val generation: Long,
    val maxDisplaySize: IntSize,
    val requirements: List<ViewportRenderRequirement>,
)

/**
 * Serializes every image update onto one processing lane and picks the next request by
 * priority rather than by launch order.
 *
 * Demand always wins: it is selected ahead of any queued prefetch, and an active prefetch pass
 * observes [shouldYieldPrefetch] at each tile boundary and returns. A native render call is
 * never interrupted mid-flight, so the lane preempts only between tiles.
 *
 * Both queues live in one atomic state object rather than behind a suspending mutex, because
 * [close] runs from the non-suspending reader shutdown path and has to drain them there.
 */
internal class ImageUpdateArbiter(
    scope: CoroutineScope,
    private val onPrefetchFailure: (Throwable) -> Unit,
) : AutoCloseable {
    private val state = MutableStateFlow(LaneState())
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
    private val prefetchFloor = MutableStateFlow(Long.MIN_VALUE)
    private val closed = MutableStateFlow(false)

    init {
        scope.launch {
            try {
                runLane()
            } finally {
                // a cancelled lane must not strand callers awaiting queued work
                cancelPending()
            }
        }
    }

    /**
     * Runs [work] on the processing lane ahead of any queued prefetch and returns once it
     * finished. The work runs in the lane's coroutine, so cancelling the caller does not
     * cancel the shared pass; failures are rethrown here.
     */
    suspend fun submitDemand(work: suspend () -> Unit) {
        if (closed.value) throw CancellationException("image update arbiter is closed")

        val entry = QueuedWork(ImageUpdatePriority.DEMAND, work)
        state.update { current -> current.copy(demand = current.demand + entry) }
        wakeUp.trySend(Unit)
        // close() may have drained the queues between the check above and the enqueue
        if (closed.value) cancelPending()
        entry.completion.await()
    }

    /**
     * Enqueues best-effort prefetch coverage. A duplicate [key] coalesces onto the queued
     * request. Returns when the pass finished, yielded, or was dropped as stale; a prefetch
     * failure is reported through `onPrefetchFailure` and never rethrown to the caller,
     * because prefetch must not break a reader that is only being optimized.
     */
    suspend fun submitPrefetch(key: PrefetchKey, work: suspend () -> Unit) {
        if (closed.value || key.generation < prefetchFloor.value) return

        val entry = QueuedWork(ImageUpdatePriority.PREFETCH, work)
        var queued = entry
        state.update { current ->
            val existing = current.prefetch[key]
            if (existing != null) {
                queued = existing
                current
            } else {
                queued = entry
                current.copy(prefetch = current.prefetch + (key to entry))
            }
        }
        wakeUp.trySend(Unit)
        if (closed.value) cancelPending()
        queued.completion.await()
    }

    /**
     * True once the running prefetch pass must stop: demand is waiting, the pass's generation
     * is stale, or the reader is shutting down. Checked after each native render call.
     */
    fun shouldYieldPrefetch(generation: Long): Boolean =
        closed.value || generation < prefetchFloor.value || state.value.demand.isNotEmpty()

    /**
     * Drops queued prefetch older than [generation] and makes an active pass of an older
     * generation yield at its next tile boundary.
     */
    fun invalidatePrefetchBefore(generation: Long) {
        prefetchFloor.update { floor -> maxOf(floor, generation) }
        wakeUp.trySend(Unit)
    }

    override fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        wakeUp.trySend(Unit)
        cancelPending()
    }

    private suspend fun runLane() {
        while (true) {
            val next = selectNext()
            if (next == null) {
                wakeUp.receive()
                continue
            }
            runEntry(next)
        }
    }

    private suspend fun runEntry(entry: QueuedWork) {
        try {
            entry.work()
            entry.completion.complete(Unit)
        } catch (e: Throwable) {
            // a cancelled lane is a shutdown, not a request failure
            currentCoroutineContext().ensureActive()
            when (entry.priority) {
                ImageUpdatePriority.DEMAND -> entry.completion.completeExceptionally(e)
                ImageUpdatePriority.PREFETCH -> {
                    entry.completion.complete(Unit)
                    if (e is CancellationException) {
                        logger.debug { "prefetch pass yielded: ${e.message}" }
                    } else {
                        onPrefetchFailure(e)
                    }
                }
            }
        }
    }

    private fun selectNext(): QueuedWork? {
        var selected: QueuedWork? = null
        var stale: List<QueuedWork> = emptyList()
        state.update { current ->
            val floor = prefetchFloor.value
            val staleKeys = current.prefetch.keys.filter { it.generation < floor }
            stale = staleKeys.map { current.prefetch.getValue(it) }
            val live = if (staleKeys.isEmpty()) current.prefetch else current.prefetch - staleKeys

            val demand = current.demand.firstOrNull()
            if (demand != null) {
                selected = demand
                LaneState(demand = current.demand.drop(1), prefetch = live)
            } else {
                // the prefetch map keeps insertion order, so the preferred neighbour that was
                // submitted first is also filled first
                val next = live.entries.firstOrNull()
                selected = next?.value
                if (next == null) LaneState(demand = current.demand, prefetch = live)
                else LaneState(demand = current.demand, prefetch = live - next.key)
            }
        }
        if (stale.isNotEmpty()) {
            logger.debug { "dropped ${stale.size} stale prefetch requests" }
            stale.forEach { it.completion.complete(Unit) }
        }
        return selected
    }

    private fun cancelPending() {
        val previous = state.getAndUpdate { LaneState() }
        previous.prefetch.values.forEach { it.completion.complete(Unit) }
        previous.demand.forEach {
            it.completion.completeExceptionally(CancellationException("image update arbiter is closed"))
        }
    }

    private class QueuedWork(
        val priority: ImageUpdatePriority,
        val work: suspend () -> Unit,
    ) {
        val completion = CompletableDeferred<Unit>()
    }

    /**
     * Entries compare by identity, so an equal-looking state can only be an identical one and
     * the compare-and-set loops in [state] cannot lose a submission.
     */
    private data class LaneState(
        val demand: List<QueuedWork> = emptyList(),
        val prefetch: Map<PrefetchKey, QueuedWork> = emptyMap(),
    )
}
