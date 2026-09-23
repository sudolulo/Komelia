package snd.komelia.image

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val displayArea = IntSize(1_000, 1_000)

private fun prefetchKey(generation: Long, region: IntRect) = PrefetchKey(
    generation = generation,
    maxDisplaySize = displayArea,
    requirements = listOf(ViewportRenderRequirement(region, 1f)),
)

private val firstRegion = IntRect(0, 0, 100, 100)
private val secondRegion = IntRect(200, 200, 300, 300)

/**
 * The arbiter's lane dispatcher processes dispatched blocks in submission order, so an empty
 * block launched after the submissions under test proves those submissions already enqueued.
 */
private suspend fun CoroutineScope.awaitDispatchedSubmissions() {
    launch { }.join()
}

class ImageUpdateArbiterTest {
    @Test
    fun `demand runs before queued prefetch`() = runBlocking {
        val lane = laneScope()
        val order = CopyOnWriteArrayList<String>()
        val arbiter = ImageUpdateArbiter(lane) { }
        try {
            val release = CompletableDeferred<Unit>()
            val occupied = CompletableDeferred<Unit>()
            val blocking = lane.launch {
                arbiter.submitDemand {
                    order += "demand-0"
                    occupied.complete(Unit)
                    release.await()
                }
            }
            withTimeout(5_000) { occupied.await() }

            val prefetch = lane.launch { arbiter.submitPrefetch(prefetchKey(1, firstRegion)) { order += "prefetch" } }
            val demand = lane.launch { arbiter.submitDemand { order += "demand-1" } }
            lane.awaitDispatchedSubmissions()

            release.complete(Unit)
            withTimeout(5_000) { joinAll(blocking, prefetch, demand) }

            assertEquals(listOf("demand-0", "demand-1", "prefetch"), order.toList())
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }

    @Test
    fun `active prefetch yields after current tile when demand arrives`() = runBlocking {
        val lane = laneScope()
        val order = CopyOnWriteArrayList<String>()
        val arbiter = ImageUpdateArbiter(lane) { }
        try {
            val key = prefetchKey(1, firstRegion)
            val tileBoundary = CompletableDeferred<Unit>()
            val firstTileDone = CompletableDeferred<Unit>()
            val prefetch = lane.launch {
                arbiter.submitPrefetch(key) {
                    order += "prefetch-tile-1"
                    firstTileDone.complete(Unit)
                    tileBoundary.await()
                    if (!arbiter.shouldYieldPrefetch(key.generation)) order += "prefetch-tile-2"
                }
            }
            withTimeout(5_000) { firstTileDone.await() }

            val demand = lane.launch { arbiter.submitDemand { order += "demand" } }
            lane.awaitDispatchedSubmissions()

            tileBoundary.complete(Unit)
            withTimeout(5_000) { joinAll(prefetch, demand) }

            assertEquals(
                listOf("prefetch-tile-1", "demand"),
                order.toList(),
                "demand must run at the next tile boundary and no second prefetch tile may start",
            )
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }

    @Test
    fun `duplicate prefetch keys coalesce`() = runBlocking {
        val lane = laneScope()
        val runs = AtomicInteger()
        val arbiter = ImageUpdateArbiter(lane) { }
        try {
            val release = CompletableDeferred<Unit>()
            val occupied = CompletableDeferred<Unit>()
            val blocking = lane.launch {
                arbiter.submitDemand {
                    occupied.complete(Unit)
                    release.await()
                }
            }
            withTimeout(5_000) { occupied.await() }

            val key = prefetchKey(1, firstRegion)
            val first = lane.launch { arbiter.submitPrefetch(key) { runs.incrementAndGet() } }
            val second = lane.launch { arbiter.submitPrefetch(key) { runs.incrementAndGet() } }
            lane.awaitDispatchedSubmissions()

            release.complete(Unit)
            withTimeout(5_000) { joinAll(blocking, first, second) }

            assertEquals(1, runs.get(), "an identical prefetch key must not render twice")
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }

    @Test
    fun `generation change cancels queued and active prefetch`() = runBlocking {
        val lane = laneScope()
        val order = CopyOnWriteArrayList<String>()
        val arbiter = ImageUpdateArbiter(lane) { }
        try {
            val activeKey = prefetchKey(1, firstRegion)
            val queuedKey = prefetchKey(1, secondRegion)
            val tileBoundary = CompletableDeferred<Unit>()
            val firstTileDone = CompletableDeferred<Unit>()
            val active = lane.launch {
                arbiter.submitPrefetch(activeKey) {
                    order += "active-tile-1"
                    firstTileDone.complete(Unit)
                    tileBoundary.await()
                    if (!arbiter.shouldYieldPrefetch(activeKey.generation)) order += "active-tile-2"
                }
            }
            withTimeout(5_000) { firstTileDone.await() }

            val queued = lane.launch { arbiter.submitPrefetch(queuedKey) { order += "queued" } }
            lane.awaitDispatchedSubmissions()

            arbiter.invalidatePrefetchBefore(2)
            tileBoundary.complete(Unit)
            withTimeout(5_000) { joinAll(active, queued) }

            assertEquals(listOf("active-tile-1"), order.toList())
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }

    @Test
    fun `cancellation is not reported as renderer error`() = runBlocking {
        val lane = laneScope()
        val failures = CopyOnWriteArrayList<Throwable>()
        val arbiter = ImageUpdateArbiter(lane) { failures += it }
        try {
            val cancelled = lane.launch {
                arbiter.submitPrefetch(prefetchKey(1, firstRegion)) {
                    throw CancellationException("prefetch yielded")
                }
            }
            withTimeout(5_000) { cancelled.join() }

            val laterDemand = CompletableDeferred<Unit>()
            lane.launch { arbiter.submitDemand { laterDemand.complete(Unit) } }
            withTimeout(5_000) { laterDemand.await() }

            assertTrue(failures.isEmpty(), "cancellation must not be reported as a prefetch failure")
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }
}

@Suppress("OPT_IN_USAGE")
private fun laneScope() = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
