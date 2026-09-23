package snd.komelia.image

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

/**
 * Serializes image update work onto one processing lane.
 *
 * The queue lives in atomic state rather than behind a suspending mutex, because [close] runs
 * from the non-suspending reader shutdown path and has to drain it there.
 */
internal class ImageUpdateArbiter(
    scope: CoroutineScope,
) : AutoCloseable {
    private val state = MutableStateFlow<List<QueuedWork>>(emptyList())
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)
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
     * Runs [work] on the processing lane and returns once it finished. The work runs in the
     * lane's coroutine, so cancelling the caller does not cancel the shared pass; failures are
     * rethrown here.
     */
    suspend fun submitDemand(work: suspend () -> Unit) {
        if (closed.value) throw CancellationException("image update arbiter is closed")

        val entry = QueuedWork(work)
        state.update { current -> current + entry }
        wakeUp.trySend(Unit)
        // close() may have drained the queues between the check above and the enqueue
        if (closed.value) cancelPending()
        entry.completion.await()
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
            entry.completion.completeExceptionally(e)
        }
    }

    private fun selectNext(): QueuedWork? {
        var selected: QueuedWork? = null
        state.update { current ->
            selected = current.firstOrNull()
            if (selected == null) current else current.drop(1)
        }
        return selected
    }

    private fun cancelPending() {
        val previous = state.getAndUpdate { emptyList() }
        previous.forEach {
            it.completion.completeExceptionally(CancellationException("image update arbiter is closed"))
        }
    }

    private class QueuedWork(
        val work: suspend () -> Unit,
    ) {
        val completion = CompletableDeferred<Unit>()
    }
}
