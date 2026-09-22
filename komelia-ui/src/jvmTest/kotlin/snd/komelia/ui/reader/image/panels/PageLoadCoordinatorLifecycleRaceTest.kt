package snd.komelia.ui.reader.image.panels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PageLoadCoordinatorLifecycleRaceTest {
    @Test
    fun `stop cleanup removes publication already inside critical section`(): Unit = runBlocking {
        val displayedPage = AtomicReference<Int?>(null)
        val pageOneCloses = AtomicInteger(0)
        val pageTwoCloses = AtomicInteger(0)
        val bothPagesClosed = CompletableDeferred<Unit>()
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = Dispatchers.Default,
            cleanupScope = this,
            closeValue = { page ->
                when (page) {
                    1 -> pageOneCloses.incrementAndGet()
                    2 -> pageTwoCloses.incrementAndGet()
                }
                if (pageOneCloses.get() > 0 && pageTwoCloses.get() > 0) {
                    bothPagesClosed.complete(Unit)
                }
            },
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                displayedPage.set(it)
                true
            },
            afterReady = {},
        ).join()
        assertEquals(1, displayedPage.get())

        val publicationEntered = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val pageTwoNavigation = coordinator.navigate(
            key = 2,
            load = { 2 },
            prepare = { it },
            publish = {
                publicationEntered.complete(Unit)
                assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                displayedPage.set(it)
                true
            },
            afterReady = {},
        )
        withTimeout(5_000) { publicationEntered.await() }

        val cleanupJob = coordinator.stop { displayedPage.set(null) }
        releasePublication.countDown()

        withTimeout(5_000) {
            pageTwoNavigation.join()
            cleanupJob.join()
        }

        assertNull(displayedPage.get())
        withTimeout(5_000) { bothPagesClosed.await() }
        assertEquals(1, pageOneCloses.get())
        assertEquals(1, pageTwoCloses.get())
    }

    @Test
    fun `navigation requested after queued unpublish publishes after it`(): Unit = runBlocking {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val displayedPage = AtomicReference<Int?>(null)
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = Dispatchers.Default,
            cleanupScope = this,
            closeValue = {},
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                events.add("publish $it")
                displayedPage.set(it)
                true
            },
            afterReady = {},
        ).join()

        val publicationEntered = CompletableDeferred<Unit>()
        val releasePublication = CountDownLatch(1)
        val pageTwoNavigation = coordinator.navigate(
            key = 2,
            load = { 2 },
            prepare = { it },
            publish = {
                publicationEntered.complete(Unit)
                assertTrue(releasePublication.await(5, TimeUnit.SECONDS))
                events.add("publish $it")
                displayedPage.set(it)
                true
            },
            afterReady = {},
        )
        withTimeout(5_000) { publicationEntered.await() }

        val unpublishJob = coordinator.unpublishCurrent {
            events.add("unpublish")
            displayedPage.set(null)
        }
        val pageThreeNavigation = coordinator.navigate(
            key = 3,
            load = { 3 },
            prepare = { it },
            publish = {
                events.add("publish $it")
                displayedPage.set(it)
                true
            },
            afterReady = {},
        )
        releasePublication.countDown()

        withTimeout(5_000) {
            pageTwoNavigation.join()
            unpublishJob.join()
            pageThreeNavigation.join()
        }

        assertEquals(
            listOf("publish 1", "publish 2", "unpublish", "publish 3"),
            events.toList(),
        )
        assertEquals(3, displayedPage.get())

        val cleanupJob = coordinator.stop { displayedPage.set(null) }
        withTimeout(5_000) { cleanupJob.join() }
    }
}
