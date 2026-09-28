package snd.komelia.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The arbiter's lane dispatcher processes dispatched blocks in submission order, so an empty
 * block launched after the submissions under test proves those submissions already enqueued.
 */
private suspend fun CoroutineScope.awaitDispatchedSubmissions() {
    launch { }.join()
}

class ImageUpdateArbiterTest {
    @Test
    fun `demand work is serialized in submission order`() = runBlocking {
        val lane = laneScope()
        val order = CopyOnWriteArrayList<String>()
        val arbiter = ImageUpdateArbiter(lane)
        try {
            val release = CompletableDeferred<Unit>()
            val occupied = CompletableDeferred<Unit>()
            val first = lane.launch {
                arbiter.submitDemand {
                    order += "demand-1"
                    occupied.complete(Unit)
                    release.await()
                }
            }
            withTimeout(5_000) { occupied.await() }

            val second = lane.launch { arbiter.submitDemand { order += "demand-2" } }
            val third = lane.launch { arbiter.submitDemand { order += "demand-3" } }
            lane.awaitDispatchedSubmissions()

            release.complete(Unit)
            withTimeout(5_000) { joinAll(first, second, third) }

            assertEquals(listOf("demand-1", "demand-2", "demand-3"), order.toList())
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }

    @Test
    fun `close cancels queued demand`() = runBlocking {
        val lane = laneScope()
        val arbiter = ImageUpdateArbiter(lane)
        val release = CompletableDeferred<Unit>()
        try {
            val occupied = CompletableDeferred<Unit>()
            val active = lane.launch {
                arbiter.submitDemand {
                    occupied.complete(Unit)
                    release.await()
                }
            }
            withTimeout(5_000) { occupied.await() }

            val queued = lane.async { arbiter.submitDemand { } }
            lane.awaitDispatchedSubmissions()
            arbiter.close()

            assertFailsWith<CancellationException> { queued.await() }
            release.complete(Unit)
            withTimeout(5_000) { active.join() }
        } finally {
            release.complete(Unit)
            arbiter.close()
            lane.cancel()
        }
    }

    @Test
    fun `a priority demand runs before demands already waiting`() = runBlocking {
        val lane = laneScope()
        val order = CopyOnWriteArrayList<String>()
        val arbiter = ImageUpdateArbiter(lane)
        try {
            val release = CompletableDeferred<Unit>()
            val occupied = CompletableDeferred<Unit>()
            val inFlight = lane.launch {
                arbiter.submitDemand {
                    order += "in-flight"
                    occupied.complete(Unit)
                    release.await()
                }
            }
            withTimeout(5_000) { occupied.await() }

            val ordinary = lane.launch { arbiter.submitDemand { order += "ordinary" } }
            lane.awaitDispatchedSubmissions()

            val priority = lane.launch { arbiter.submitDemand(priority = true) { order += "priority" } }
            lane.awaitDispatchedSubmissions()

            release.complete(Unit)
            withTimeout(5_000) { joinAll(inFlight, ordinary, priority) }

            assertEquals(listOf("in-flight", "priority", "ordinary"), order.toList())
        } finally {
            arbiter.close()
            lane.cancel()
        }
    }
}

@Suppress("OPT_IN_USAGE")
private fun laneScope() = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
