package snd.komelia.ui.reader.image.panels

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PageLoadCoordinatorTest {
    @Test
    fun `canceling obsolete navigation preserves shared in-progress page load`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            closeValue = {},
        )
        val pageOneReady = CompletableDeferred<Unit>()
        var pageOneStarts = 0
        val publishedPages = mutableListOf<Int>()

        val pageOneLoad = coordinator.prefetch(1) {
            pageOneStarts++
            pageOneReady.await()
            1
        }
        coordinator.navigate(
            key = 1,
            load = { error("cached page one load was restarted") },
            prepare = { it },
            publish = publishedPages::add,
            afterReady = {},
        )
        runCurrent()

        coordinator.navigate(
            key = 2,
            load = { 2 },
            prepare = { it },
            publish = publishedPages::add,
            afterReady = {},
        )
        runCurrent()

        assertTrue(pageOneLoad.isActive)
        assertFalse(pageOneLoad.isCancelled)
        assertEquals(1, pageOneStarts)
        assertEquals(listOf(2), publishedPages)

        coordinator.navigate(
            key = 1,
            load = { error("cached page one load was restarted") },
            prepare = { it },
            publish = publishedPages::add,
            afterReady = {},
        )
        pageOneReady.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, pageOneStarts)
        assertEquals(listOf(2, 1), publishedPages)
        coordinator.stop {}
    }

    @Test
    fun `page publishes and adjacent prefetch starts only after first painter is ready`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = PageLoadCoordinator<Int, TestPage>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            closeValue = {},
        )
        val firstPainterReady = CompletableDeferred<Unit>()
        val target = TestPage(pageNumber = 4, firstPainterReady = firstPainterReady)
        val publishedPages = mutableListOf<TestPage>()
        var adjacentPrefetchStarted = false

        coordinator.navigate(
            key = target.pageNumber,
            load = { target },
            prepare = {
                it.firstPainterReady.await()
                it
            },
            publish = publishedPages::add,
            afterReady = { adjacentPrefetchStarted = true },
        )
        runCurrent()

        assertTrue(publishedPages.isEmpty())
        assertFalse(adjacentPrefetchStarted)

        firstPainterReady.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(target), publishedPages)
        assertTrue(adjacentPrefetchStarted)
        coordinator.stop {}
    }

    @Test
    fun `stop closes completed pages and cancels active page loads`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            closeValue = closedPages::add,
        )
        var activeLoadCleanedUp = false

        val completedLoad = coordinator.prefetch(1) { 1 }
        val activeLoad = coordinator.prefetch(2) {
            try {
                awaitCancellation()
            } finally {
                activeLoadCleanedUp = true
            }
        }
        runCurrent()
        assertTrue(completedLoad.isCompleted)
        assertTrue(activeLoad.isActive)

        coordinator.stop {}
        runCurrent()

        assertEquals(listOf(1), closedPages)
        assertTrue(activeLoad.isCancelled)
        assertTrue(activeLoadCleanedUp)
    }

    @Test
    fun `stop leaves coordinator reusable for a later navigation`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            closeValue = {},
        )
        val publishedPages = mutableListOf<Int>()

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = publishedPages::add,
            afterReady = {},
        )
        advanceUntilIdle()
        coordinator.stop {}
        runCurrent()
        coordinator.navigate(
            key = 2,
            load = { 2 },
            prepare = { it },
            publish = publishedPages::add,
            afterReady = {},
        )
        advanceUntilIdle()

        assertEquals(listOf(1, 2), publishedPages)
        coordinator.stop {}
    }

    @Test
    fun `evicted current page stays open until its replacement is published`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        val publishedPages = mutableListOf<Int>()
        var displayedPage: Int? = null
        var closedAtSecondPublication: List<Int>? = null
        var secondPagePublished = false
        var secondPageWasPublishedWhenFirstPageClosed: Boolean? = null
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            maximumCacheSize = 1,
            closeValue = {
                if (it == 1) {
                    secondPageWasPublishedWhenFirstPageClosed = secondPagePublished
                }
                assertFalse(it == displayedPage, "displayed page $it was closed")
                closedPages.add(it)
            },
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                displayedPage = it
                publishedPages.add(it)
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()
        coordinator.prefetch(2) { 2 }
        advanceUntilIdle()

        assertEquals(emptyList(), closedPages)

        coordinator.navigate(
            key = 2,
            load = { error("cached page two load was restarted") },
            prepare = { it },
            publish = {
                closedAtSecondPublication = closedPages.toList()
                displayedPage = it
                publishedPages.add(it)
                secondPagePublished = true
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()
        runCurrent()

        assertEquals(emptyList(), closedAtSecondPublication)
        assertEquals(listOf(1, 2), publishedPages)
        assertEquals(listOf(1), closedPages)
        assertEquals(true, secondPageWasPublishedWhenFirstPageClosed)

        coordinator.stop { displayedPage = null }
        runCurrent()
        coordinator.stop {}
        runCurrent()

        assertEquals(listOf(1, 2), closedPages)
    }

    @Test
    fun `rejected publication keeps displayed page current and disposes rejected page after eviction`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        val rejectedPageClosed = CompletableDeferred<Unit>()
        var displayedPage: Int? = null
        var adjacentPrefetchStarted = false
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            maximumCacheSize = 1,
            closeValue = {
                closedPages.add(it)
                if (it == 2) rejectedPageClosed.complete(Unit)
            },
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()

        coordinator.navigate(
            key = 2,
            load = { 2 },
            prepare = { it },
            publish = { false },
            afterReady = { adjacentPrefetchStarted = true },
        )
        advanceUntilIdle()
        coordinator.prefetch(3) { 3 }
        advanceUntilIdle()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { rejectedPageClosed.await() }
        }

        assertEquals(1, displayedPage)
        assertEquals(listOf(2), closedPages)
        assertFalse(adjacentPrefetchStarted)

        coordinator.stop { displayedPage = null }
        advanceUntilIdle()
    }

    @Test
    fun `cancellation from successful publish keeps displayed page owned until unpublish`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        var displayedPage: Int? = null
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            maximumCacheSize = 1,
            closeValue = closedPages::add,
        )
        lateinit var navigationContextJob: Job

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = {
                navigationContextJob = currentCoroutineContext()[Job]!!
                it
            },
            publish = {
                displayedPage = it
                navigationContextJob.cancel()
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()

        coordinator.prefetch(2) { 2 }
        runCurrent()

        assertEquals(1, displayedPage)
        assertFalse(1 in closedPages, "displayed page was closed after cache eviction")

        coordinator.unpublishCurrent { displayedPage = null }
        runCurrent()

        assertEquals(null, displayedPage)
        assertEquals(1, closedPages.count { it == 1 })

        coordinator.stop {}
        advanceUntilIdle()
    }

    @Test
    fun `canceled navigate replacement leaves displayed page owned through later eviction`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        val canceledPageClosed = CompletableDeferred<Unit>()
        var displayedPage: Int? = null
        val pageTwoPrepareStarted = CompletableDeferred<Unit>()
        val pageThreePrepare = CompletableDeferred<Unit>()
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            maximumCacheSize = 1,
            closeValue = {
                closedPages.add(it)
                if (it == 2) canceledPageClosed.complete(Unit)
            },
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()

        coordinator.navigate(
            key = 2,
            load = { 2 },
            prepare = {
                pageTwoPrepareStarted.complete(Unit)
                awaitCancellation()
            },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        runCurrent()
        assertTrue(pageTwoPrepareStarted.isCompleted)

        coordinator.navigate(
            key = 3,
            load = { 3 },
            prepare = {
                pageThreePrepare.await()
                it
            },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        runCurrent()
        coordinator.prefetch(4) { 4 }
        runCurrent()
        withContext(Dispatchers.Default) {
            withTimeout(5_000) { canceledPageClosed.await() }
        }

        assertEquals(1, displayedPage)
        assertFalse(1 in closedPages)
        assertTrue(2 in closedPages)

        coordinator.unpublishCurrent { displayedPage = null }
        runCurrent()

        assertEquals(null, displayedPage)
        assertEquals(1, closedPages.count { it == 1 })

        coordinator.stop {}
        advanceUntilIdle()
    }

    @Test
    fun `failed navigate replacement leaves displayed page owned through later eviction`() {
        val failure = assertFailsWith<IllegalStateException> {
            runTest {
                val dispatcher = StandardTestDispatcher(testScheduler)
                val closedPages = mutableListOf<Int>()
                var displayedPage: Int? = null
                val coordinator = PageLoadCoordinator<Int, Int>(
                    dispatcher = dispatcher,
                    cleanupScope = backgroundScope,
                    maximumCacheSize = 1,
                    closeValue = closedPages::add,
                )

                coordinator.navigate(
                    key = 1,
                    load = { 1 },
                    prepare = { it },
                    publish = {
                        displayedPage = it
                        true
                    },
                    afterReady = {},
                )
                advanceUntilIdle()

                coordinator.navigate(
                    key = 2,
                    load = { 2 },
                    prepare = { throw IllegalStateException("replacement failed") },
                    publish = {
                        displayedPage = it
                        true
                    },
                    afterReady = {},
                )
                advanceUntilIdle()
                coordinator.prefetch(3) { 3 }
                advanceUntilIdle()

                assertEquals(1, displayedPage)
                assertFalse(1 in closedPages)

                coordinator.unpublishCurrent { displayedPage = null }
                runCurrent()

                assertEquals(null, displayedPage)
                assertEquals(1, closedPages.count { it == 1 })

                coordinator.stop {}
                advanceUntilIdle()
            }
        }

        assertEquals("replacement failed", failure.message)
    }

    @Test
    fun `canceled replacement after explicit unpublish closes prior page exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        var displayedPage: Int? = null
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            maximumCacheSize = 1,
            closeValue = closedPages::add,
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()

        coordinator.unpublishCurrent { displayedPage = null }
        coordinator.unpublishCurrent { displayedPage = null }
        val replacementStarted = CompletableDeferred<Unit>()
        coordinator.navigate(
            key = 2,
            load = {
                replacementStarted.complete(Unit)
                awaitCancellation()
            },
            prepare = { it },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        runCurrent()
        coordinator.stop { displayedPage = null }
        coordinator.stop { displayedPage = null }
        advanceUntilIdle()

        assertTrue(replacementStarted.isCompleted)
        assertEquals(null, displayedPage)
        assertEquals(listOf(1), closedPages)
    }

    @Test
    fun `failed replacement after explicit unpublish closes prior page exactly once`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val closedPages = mutableListOf<Int>()
        var displayedPage: Int? = null
        val coordinator = PageLoadCoordinator<Int, Int>(
            dispatcher = dispatcher,
            cleanupScope = backgroundScope,
            maximumCacheSize = 1,
            closeValue = closedPages::add,
        )

        coordinator.navigate(
            key = 1,
            load = { 1 },
            prepare = { it },
            publish = {
                displayedPage = it
                true
            },
            afterReady = {},
        )
        advanceUntilIdle()

        coordinator.unpublishCurrent { displayedPage = null }
        coordinator.unpublishCurrent { displayedPage = null }
        val failedReplacement = coordinator.prefetch(2) {
            throw IllegalStateException("replacement failed")
        }
        runCurrent()
        val replacementFailure = runCatching { failedReplacement.await() }.exceptionOrNull()
        coordinator.stop { displayedPage = null }
        coordinator.stop { displayedPage = null }
        advanceUntilIdle()

        assertTrue(replacementFailure is IllegalStateException)
        assertEquals("replacement failed", replacementFailure.message)
        assertEquals(null, displayedPage)
        assertEquals(listOf(1), closedPages)
    }

    private data class TestPage(
        val pageNumber: Int,
        val firstPainterReady: CompletableDeferred<Unit>,
    )
}
