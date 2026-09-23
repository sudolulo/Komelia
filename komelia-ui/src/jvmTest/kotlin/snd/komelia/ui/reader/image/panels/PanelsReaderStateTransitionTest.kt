package snd.komelia.ui.reader.image.panels

import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import snd.komelia.AppNotifications
import snd.komelia.color.repository.BookColorCorrectionRepository
import snd.komelia.image.BookImageLoader
import snd.komelia.image.ImageRect
import snd.komelia.image.KomeliaImage
import snd.komelia.image.KomeliaImageDecoder
import snd.komelia.image.KomeliaPanelDetector
import snd.komelia.image.ReaderImage
import snd.komelia.image.PreparedUpdateLease
import snd.komelia.image.ReaderImageFactory
import snd.komelia.image.ReaderImageResult
import snd.komelia.image.ViewportRenderRequirement
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.KomgaReadListApi
import snd.komelia.komga.api.KomgaSeriesApi
import snd.komelia.onnxruntime.OnnxRuntimeExecutionProvider
import snd.komelia.onnxruntime.OnnxRuntimeRfDetr
import snd.komelia.settings.ImageReaderSettingsRepository
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.settings.model.PanelTransitionMode
import snd.komelia.ui.BookSiblingsContext
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.ReaderState
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komga.client.book.KomgaBookId
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val fixtureAreaSize = IntSize(100, 100)
private val fixtureImageSize = IntSize(300, 300)

// three non overlapping panels of a 300x300 page fitted into a 100x100 area; every panel fills
// the area at zoom 3, so panel 1 sits at offset zero, panel 0 at (100, 100) and panel 2 at
// (-100, -100)
private val defaultPanels = listOf(
    ImageRect(0, 0, 100, 100),
    ImageRect(100, 100, 200, 200),
    ImageRect(200, 200, 300, 300),
)

/** the fitted page in display coordinates; the retained page frame covers exactly this */
private val pageRegion = IntRect(0, 0, 100, 100)

private val sourceRegion = IntRect(33, 33, 66, 66)
private val nextDestinationRegion = IntRect(67, 67, 100, 100)
private val previousDestinationRegion = IntRect(0, 0, 33, 33)

private fun IntRect.containsRect(other: IntRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

/**
 * The panel zoom is derived through float scale arithmetic, so it lands within rounding distance
 * of 3 rather than exactly on it. The fitted regions are integers and are compared exactly.
 */
private fun assertRequirements(
    expectedRegions: List<IntRect>,
    expectedZoom: Float,
    actual: List<ViewportRenderRequirement>,
) {
    assertEquals(expectedRegions, actual.map { it.region })
    for (requirement in actual) {
        assertEquals(expectedZoom, requirement.zoomFactor, 0.0001f)
    }
}

class PanelsReaderStateTransitionTest {
    @Test
    fun `snappy uses 250 milliseconds`() {
        assertEquals(250, PanelTransitionMode.SNAPPY.durationMillis())
    }

    @Test
    fun `classic uses stock 1000 milliseconds`() {
        assertEquals(1000, PanelTransitionMode.CLASSIC.durationMillis())
    }

    @Test
    fun `painter is never null during a panel transition`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            val samples = CopyOnWriteArrayList<TransitionSample>()
            val watcher = launch(Dispatchers.Default) {
                fixture.screenScaleState.transformation.collect { transformation ->
                    samples += TransitionSample(
                        visibleRegion = visibleDisplayRegion(
                            displaySize = fixture.screenScaleState.targetSize.value.toIntSize(),
                            areaSize = fixture.screenScaleState.areaSize.value,
                            transformation = transformation,
                        ),
                        painterInstalled = fixture.image.painter.value != null,
                        installedCoverage = fixture.image.installedCoverage,
                    )
                }
            }

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            withTimeout(2_000) {
                while (fixture.image.viewportUpdates.isEmpty()) delay(10)
            }
            watcher.cancelAndJoin()

            assertTrue(
                samples.any { it.visibleRegion != sourceRegion },
                "the transition must have moved the viewport, sampled ${samples.map { it.visibleRegion }}",
            )
            assertTrue(
                samples.all { it.painterInstalled },
                "painter was null during the transition at some viewport",
            )
            val uncovered = samples.filterNot { sample ->
                sample.installedCoverage?.containsRect(sample.visibleRegion) == true
            }
            assertTrue(
                uncovered.isEmpty(),
                "the installed frame left a swept viewport uncovered: $uncovered",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `transition publishes the page frame before changing zoom or offset`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            fixture.image.blockPageFramePublication()
            val startTransformation = fixture.screenScaleState.transformation.value

            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.pageFramePublicationStarted.await() }

            assertEquals(PanelsReaderState.PageIndex(0, 1, false), fixture.state.currentPageIndex.value)
            assertEquals(startTransformation, fixture.screenScaleState.transformation.value)

            fixture.image.releasePageFramePublication()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertEquals(listOf(Publication.PAGE_FRAME), fixture.image.publications.toList())
            assertEquals(pageRegion, fixture.image.installedCoverage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `transition performs zero native renders before movement starts`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            val rendersBeforeTransition = fixture.image.nativeRenders

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertEquals(
                rendersBeforeTransition,
                fixture.image.nativeRenders,
                "movement must start without rendering anything",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `destination viewport is rendered after movement and published as a second frame`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            // the movement is still running, so nothing but the page frame may be installed
            repeat(10) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            delay(50)
            assertEquals(emptyList(), fixture.image.viewportUpdates.toList())
            assertEquals(listOf(Publication.PAGE_FRAME), fixture.image.publications.toList())

            repeat(60) { fixture.frameClock.sendFrame((it + 10) * 16_000_000L) }
            withTimeout(2_000) {
                while (fixture.image.viewportUpdates.isEmpty()) delay(10)
            }
            delay(250)

            val destination = fixture.image.viewportUpdates.single()
            assertEquals(fixtureAreaSize, destination.maxDisplaySize)
            assertEquals(nextDestinationRegion, destination.visibleDisplaySize)
            assertEquals(3f, destination.zoomFactor, 0.0001f)
            assertEquals(
                listOf(Publication.PAGE_FRAME, Publication.TILED),
                fixture.image.publications.toList(),
                "the destination must arrive as a second frame after the page frame",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an ordinary requestUpdate mid animation does not publish over the page frame`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            // every animation frame publishes a new transformation, which is exactly the input
            // the conflated viewport observer turns into an ordinary update when it is not held
            repeat(10) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            delay(100)

            assertEquals(
                emptyList(),
                fixture.image.viewportUpdates.toList(),
                "no ordinary viewport update may be issued while the page frame lease is held",
            )
            assertEquals(pageRegion, fixture.image.installedCoverage)
            assertEquals(0, fixture.image.pageFrameLeaseReleases)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelling mid animation leaves the page frame published and releases ownership once`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            val samples = CopyOnWriteArrayList<TransitionSample>()
            val watcher = launch(Dispatchers.Default) {
                fixture.screenScaleState.transformation.collect { transformation ->
                    samples += TransitionSample(
                        visibleRegion = visibleDisplayRegion(
                            displaySize = fixture.screenScaleState.targetSize.value.toIntSize(),
                            areaSize = fixture.screenScaleState.areaSize.value,
                            transformation = transformation,
                        ),
                        painterInstalled = fixture.image.painter.value != null,
                        installedCoverage = fixture.image.installedCoverage,
                    )
                }
            }

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
            repeat(4) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            // no further frames arrive, so the movement quiesces part way; the watcher lags the
            // transformation flow, so the stop point has to be read from the flow itself
            val stoppedAt = visibleDisplayRegion(
                displaySize = fixture.screenScaleState.targetSize.value.toIntSize(),
                areaSize = fixture.screenScaleState.areaSize.value,
                transformation = fixture.awaitStableTransformation(),
            )
            assertNotEquals(sourceRegion, stoppedAt, "the movement must have started before it is cancelled")

            fixture.state.stop()
            delay(250)
            watcher.cancelAndJoin()

            // the page frame stayed installed for every swept viewport, cancellation included
            assertTrue(
                samples.all { it.painterInstalled },
                "painter was null while the cancelled transition unwound",
            )
            val uncovered = samples.filterNot { sample ->
                sample.installedCoverage?.containsRect(sample.visibleRegion) == true
            }
            assertTrue(uncovered.isEmpty(), "a cancelled transition left a viewport uncovered: $uncovered")

            assertEquals(
                1,
                fixture.image.pageFrameLeaseReleases,
                "the page frame lease must be released exactly once",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a transition before the initial paint takes the waiting fallback`() = runBlocking {
        val fixture = panelFixture(pageFrameAvailable = false)
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            val startTransformation = fixture.screenScaleState.transformation.value

            fixture.state.nextPanel()
            val fallback = withTimeout(2_000) { fixture.image.preparationStarted.await() }

            // the destination is rendered before anything moves, which is the one case where
            // movement is allowed to wait
            assertEquals(fixtureAreaSize, fallback.maxDisplaySize)
            assertRequirements(listOf(nextDestinationRegion), 3f, fallback.requirements)
            assertEquals(startTransformation, fixture.screenScaleState.transformation.value)
            assertEquals(PanelsReaderState.PageIndex(0, 1, false), fixture.state.currentPageIndex.value)

            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertEquals(listOf(Publication.TILED), fixture.image.publications.toList())
            assertEquals(0, fixture.image.pageFramePublications)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `snappy requests 250ms and classic requests 1000ms, snapshotted at input`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val transitionMode = MutableStateFlow(PanelTransitionMode.CLASSIC)
        val fixture = panelFixture(transitionMode = transitionMode, diagnostics = { events += it })
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            // a mode change after the input was accepted must not shorten the movement already
            // under way: 250 ms of frames leave a 1000 ms animation unfinished
            fixture.frameClock.sendFrame(0L)
            fixture.frameClock.sendFrame(250_000_000L)
            delay(50)
            transitionMode.value = PanelTransitionMode.SNAPPY
            assertEquals(emptyList(), events.toList())

            fixture.frameClock.sendFrame(1_000_000_000L)
            withTimeout(2_000) {
                while (events.isEmpty()) delay(10)
            }
            assertEquals(1000, events.single().durationMillis)

            // the event is emitted before the controller returns to idle, so the next input can
            // still be refused as a repeated tap; retry it until it is accepted
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 1) {
                    fixture.state.previousPanel()
                    delay(20)
                }
            }
            fixture.frameClock.sendFrame(1_100_000_000L)
            fixture.frameClock.sendFrame(1_350_000_000L)
            withTimeout(2_000) {
                while (events.size < 2) delay(10)
            }

            assertEquals(250, events[1].durationMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `stable panel prefetches preferred next then previous`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()

            val requests = fixture.image.prefetchRequests.toList()
            assertEquals(2, requests.size)
            assertRequirements(listOf(nextDestinationRegion), 3f, requests[0].requirements)
            assertRequirements(listOf(previousDestinationRegion), 3f, requests[1].requirements)
            assertTrue(requests.all { it.maxDisplaySize == fixtureAreaSize })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `page or geometry change invalidates scheduled prefetch`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()
            fixture.image.blockPrefetch()
            fixture.image.prefetchRequests.clear()
            fixture.screenScaleState.setOffset(Offset(2f, 2f))
            withTimeout(2_000) {
                while (fixture.image.prefetchRequests.isEmpty()) delay(10)
            }

            fixture.screenScaleState.setAreaSize(IntSize(120, 120))

            withTimeout(2_000) {
                while (fixture.image.prefetchCancellations == 0) delay(10)
            }
            withTimeout(2_000) {
                while (fixture.image.prefetchRequests.none { it.maxDisplaySize == IntSize(120, 120) }) {
                    delay(10)
                }
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `prefetch failure does not break current reader`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.prefetchFailure = IllegalStateException("prefetch render failed")
            fixture.awaitViewportObserver()
            withTimeout(2_000) {
                while (fixture.image.prefetchRequests.isEmpty()) delay(10)
            }

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertNull(fixture.image.error.value)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `blocked prefetch does not delay a panel transition`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            withTimeout(2_000) {
                while (fixture.image.prefetchRequests.isEmpty()) delay(10)
            }

            fixture.state.nextPanel()

            // the transition publishes and moves while the prefetch is still suspended
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `warm snappy transition emits one correlated diagnostic event`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(diagnostics = { events += it })
        try {
            fixture.settle()

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
            // 250 ms of movement at 16 ms per frame needs far fewer than this
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            withTimeout(2_000) {
                while (events.isEmpty()) delay(10)
            }

            val event = events.single()
            assertEquals(PanelTransitionMode.SNAPPY, event.mode)
            assertEquals(250, event.durationMillis)
            assertEquals(PanelTransitionOutcome.ANIMATED, event.outcome)
            assertTrue(event.pageFrameAvailable)
            assertTrue(event.generation > 0)
            assertTrue(event.readinessMillis >= 0)
            assertTrue(event.movementEndMillis >= event.movementStartMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelled transition emits a cancellation event and no error event`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(pageFrameAvailable = false, diagnostics = { events += it })
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            // the fallback render never completes, so the transition is cancelled while preparing
            fixture.state.stop()

            withTimeout(2_000) {
                while (events.isEmpty()) delay(10)
            }

            val event = events.single()
            assertEquals(PanelTransitionOutcome.CANCELLED, event.outcome)
            assertTrue(
                events.none { it.outcome == PanelTransitionOutcome.FAILED },
                "a cancellation must never be reported as a renderer failure, got $events",
            )
        } finally {
            fixture.close()
        }
    }
}

private class ControlledFrameClock : MonotonicFrameClock {
    private val frames = Channel<Long>(Channel.UNLIMITED)

    suspend fun sendFrame(timeNanos: Long) {
        frames.send(timeNanos)
    }

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R = onFrame(frames.receive())
}

/** one sampled point of a transition: where the viewport was and what was drawable there */
private data class TransitionSample(
    val visibleRegion: IntRect,
    val painterInstalled: Boolean,
    val installedCoverage: IntRect?,
)

private class PanelFixture(
    val state: PanelsReaderState,
    val image: ControlledReaderImage,
    val screenScaleState: ScreenScaleState,
    val frameClock: ControlledFrameClock,
    private val animationScope: CoroutineScope,
) {
    /** drives the viewport until the reader's conflated observers are demonstrably running */
    suspend fun awaitViewportObserver() {
        withTimeout(2_000) {
            var offset = 1f
            while (image.viewportUpdates.isEmpty()) {
                screenScaleState.setOffset(Offset(offset, offset))
                offset = if (offset == 1f) 2f else 1f
                delay(10)
            }
        }
        delay(150)
        image.viewportUpdates.clear()
        screenScaleState.setOffset(Offset.Zero)
        withTimeout(2_000) {
            while (image.viewportUpdates.isEmpty()) delay(10)
        }
        delay(150)
        image.viewportUpdates.clear()
        image.publications.clear()
    }

    /** the transformation the movement has settled on, once no further frame changes it */
    suspend fun awaitStableTransformation(): ScreenScaleState.Transformation = withTimeout(2_000) {
        var last = screenScaleState.transformation.value
        while (true) {
            delay(100)
            val current = screenScaleState.transformation.value
            if (current == last) return@withTimeout current
            last = current
        }
        @Suppress("UNREACHABLE_CODE")
        last
    }

    /**
     * Leaves the reader on a stable panel with both neighbours prefetched. The settled viewport
     * offset of (1, 1) rounds to the same fitted regions as offset zero, so the recorded
     * requirements are the ones the tests assert on.
     */
    suspend fun settle() {
        awaitViewportObserver()
        delay(400)
        image.prefetchRequests.clear()
        screenScaleState.setOffset(Offset(1f, 1f))
        withTimeout(2_000) {
            while (image.prefetchRequests.size < 2) delay(10)
        }
        delay(150)
        image.viewportUpdates.clear()
        image.publications.clear()
    }

    fun close() {
        state.stop()
        animationScope.cancel()
    }
}

@OptIn(InternalVoyagerApi::class)
private suspend fun CoroutineScope.panelFixture(
    panels: List<ImageRect> = defaultPanels,
    startPanel: Int = 1,
    pageFrameAvailable: Boolean = true,
    transitionMode: MutableStateFlow<PanelTransitionMode> = MutableStateFlow(PanelTransitionMode.SNAPPY),
    diagnostics: PanelTransitionDiagnostics = PanelTransitionDiagnostics {},
): PanelFixture {
    val image = ControlledReaderImage(fixtureImageSize, pageFrameAvailable)
    val frameClock = ControlledFrameClock()
    val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + frameClock)
    val screenScaleState = ScreenScaleState().apply {
        composeScope = animationScope
        setAreaSize(fixtureAreaSize)
        setTargetSize(fixtureAreaSize.toSize())
        setZoom(3f)
    }
    val state = panelsReaderState(screenScaleState, transitionMode, diagnostics)
    state.currentPage.value = PanelsReaderState.PanelsPage(
        metadata = PageMetadata(KomgaBookId("book"), 1, fixtureImageSize),
        imageResult = ReaderImageResult.Success(image),
        panelData = PanelsReaderState.PanelData(
            panels = panels,
            originalImageSize = fixtureImageSize,
            panelCoversMajorityOfImage = false,
        ),
    )
    state.currentPageIndex.value = PanelsReaderState.PageIndex(0, startPanel, false)
    state.initialize()
    return PanelFixture(state, image, screenScaleState, frameClock, animationScope)
}

private data class ImageUpdate(
    val maxDisplaySize: IntSize,
    val zoomFactor: Float,
    val visibleDisplaySize: IntRect,
)

private data class PrefetchRequest(
    val maxDisplaySize: IntSize,
    val requirements: List<ViewportRenderRequirement>,
)

private data class PrepareRequest(
    val maxDisplaySize: IntSize,
    val requirements: List<ViewportRenderRequirement>,
)

/** which kind of frame a publication installed, in the order the renderer installed them */
private enum class Publication { PAGE_FRAME, TILED }

private class FixturePainter(private val name: String) : Painter() {
    override val intrinsicSize = Size.Zero
    override fun DrawScope.onDraw() = Unit
    override fun toString() = name
}

/**
 * Models a replacement-based renderer closely enough to observe what a transition draws: exactly
 * one frame is installed at a time, it covers a known region of the fitted page, and installing a
 * region that was never rendered costs one native render.
 *
 * The double is deliberately naive about ordering: it installs whatever it is told to install, so
 * an update the transition should not have issued shows up as a shrunken coverage rather than
 * being silently absorbed.
 */
private class ControlledReaderImage(
    imageSize: IntSize,
    pageFrameAvailable: Boolean,
) : ReaderImage {
    val preparationStarted = CompletableDeferred<PrepareRequest>()
    val releasePreparation = CompletableDeferred<Unit>()
    val pageFramePublicationStarted = CompletableDeferred<Unit>()

    val viewportUpdates = Collections.synchronizedList(mutableListOf<ImageUpdate>())
    val prefetchRequests = CopyOnWriteArrayList<PrefetchRequest>()
    val prepareRequests = CopyOnWriteArrayList<PrepareRequest>()
    val publications = CopyOnWriteArrayList<Publication>()

    private val renderedCoverage = Collections.synchronizedSet(mutableSetOf<ViewportRenderRequirement>())
    private val renders = AtomicInteger()
    private val prefetchCancels = AtomicInteger()
    private val pageFramePublishCount = AtomicInteger()
    private val pageFrameReleases = AtomicInteger()
    private val coverage = AtomicReference<IntRect?>(null)
    private val prefetchGate = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })
    private val pageFrameGate = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })
    private val pageFramePainter = FixturePainter("page-frame")

    @Volatile
    var pageFrameAvailable: Boolean = pageFrameAvailable

    @Volatile
    var prefetchFailure: Throwable? = null

    val nativeRenders: Int get() = renders.get()
    val prefetchCancellations: Int get() = prefetchCancels.get()
    val pageFramePublications: Int get() = pageFramePublishCount.get()
    val pageFrameLeaseReleases: Int get() = pageFrameReleases.get()

    /** the fitted display region the currently installed frame can draw */
    val installedCoverage: IntRect? get() = coverage.get()

    fun blockPrefetch() {
        prefetchGate.set(CompletableDeferred())
    }

    fun blockPageFramePublication() {
        pageFrameGate.set(CompletableDeferred())
    }

    fun releasePageFramePublication() {
        pageFrameGate.get().complete(Unit)
    }

    override val pageId = ReaderImage.PageId("book", 1)
    override val originalSize = MutableStateFlow<IntSize?>(imageSize)
    override val displaySize = MutableStateFlow<IntSize?>(imageSize)
    override val currentSize = MutableStateFlow<IntSize?>(imageSize)
    override val painter = MutableStateFlow<Painter?>(null)
    override val error = MutableStateFlow<Throwable?>(null)

    override fun requestUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ) {
        viewportUpdates += ImageUpdate(maxDisplaySize, zoomFactor, visibleDisplaySize)
        render(listOf(ViewportRenderRequirement(visibleDisplaySize, zoomFactor)))
        install(Publication.TILED, visibleDisplaySize, FixturePainter("tiled $visibleDisplaySize"))
    }

    override suspend fun publishPageFrame(): PreparedUpdateLease? {
        pageFramePublicationStarted.complete(Unit)
        pageFrameGate.get().await()
        if (!pageFrameAvailable) return null

        pageFramePublishCount.incrementAndGet()
        install(Publication.PAGE_FRAME, pageRegion, pageFramePainter)
        return PreparedUpdateLease { pageFrameReleases.incrementAndGet() }
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease {
        val request = PrepareRequest(maxDisplaySize, requirements)
        prepareRequests += request
        preparationStarted.complete(request)
        releasePreparation.await()

        render(requirements)
        val installed = requirements.map { it.region }.reduce { left, right ->
            IntRect(
                left = minOf(left.left, right.left),
                top = minOf(left.top, right.top),
                right = maxOf(left.right, right.right),
                bottom = maxOf(left.bottom, right.bottom),
            )
        }
        install(Publication.TILED, installed, FixturePainter("tiled $installed"))
        return PreparedUpdateLease {}
    }

    override suspend fun prefetchUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ) {
        prefetchRequests += PrefetchRequest(maxDisplaySize, requirements)
        try {
            prefetchGate.get().await()
        } catch (e: CancellationException) {
            prefetchCancels.incrementAndGet()
            throw e
        }
        prefetchFailure?.let { throw it }
        render(requirements)
    }

    private fun install(publication: Publication, region: IntRect, frame: Painter) {
        coverage.set(region)
        painter.value = frame
        publications += publication
    }

    /** one requirement stands in for one tile here, so the counts are directly assertable */
    private fun render(requirements: List<ViewportRenderRequirement>) {
        for (requirement in requirements.toSet()) {
            if (renderedCoverage.add(requirement)) renders.incrementAndGet()
        }
    }

    override suspend fun getOriginalImageSize(): Result<IntSize> =
        Result.success(checkNotNull(originalSize.value))

    override suspend fun getOriginalImage(): Result<KomeliaImage> =
        Result.failure(UnsupportedOperationException())

    override fun close() = Unit
}

@OptIn(InternalVoyagerApi::class)
private fun CoroutineScope.panelsReaderState(
    screenScaleState: ScreenScaleState,
    transitionMode: MutableStateFlow<PanelTransitionMode> = MutableStateFlow(PanelTransitionMode.SNAPPY),
    diagnostics: PanelTransitionDiagnostics = PanelTransitionDiagnostics {},
): PanelsReaderState {
    val bookApi = unsupportedProxy<KomgaBookApi>()
    val settingsRepository = settingsRepository(transitionMode)
    val pageChangeFlow = MutableSharedFlow<Unit>()
    val readerState = ReaderState(
        bookApi = bookApi,
        seriesApi = unsupportedProxy<KomgaSeriesApi>(),
        readListApi = unsupportedProxy<KomgaReadListApi>(),
        navigator = Navigator(
            screens = listOf(unsupportedProxy<Screen>()),
            key = "panel-transition-test",
            stateHolder = unsupportedProxy<SaveableStateHolder>(),
            disposeBehavior = NavigatorDisposeBehavior(),
            parent = null,
        ),
        appNotifications = AppNotifications(),
        readerSettingsRepository = settingsRepository,
        currentBookId = MutableStateFlow(null),
        markReadProgress = false,
        stateScope = this,
        bookSiblingsContext = BookSiblingsContext.Series,
        colorCorrectionRepository = unsupportedProxy<BookColorCorrectionRepository>(),
        pageChangeFlow = pageChangeFlow,
    )
    val imageLoader = BookImageLoader(
        bookClient = MutableStateFlow(bookApi),
        imageDecoder = unsupportedProxy<KomeliaImageDecoder>(),
        readerImageFactory = unsupportedProxy<ReaderImageFactory>(),
        diskCache = null,
    )
    val panelDetector = object : KomeliaPanelDetector(
        ortRfDetr = unsupportedProxy<OnnxRuntimeRfDetr>(),
        executionProvider = OnnxRuntimeExecutionProvider.CPU,
        deviceId = MutableStateFlow(0),
        updateFlow = emptyFlow(),
    ) {
        override fun getModelPath(): String? = null
    }

    return PanelsReaderState(
        cleanupScope = this,
        settingsRepository = settingsRepository,
        appNotifications = AppNotifications(),
        readerState = readerState,
        imageLoader = imageLoader,
        pageChangeFlow = pageChangeFlow,
        onnxRuntimeRfDetr = panelDetector,
        screenScaleState = screenScaleState,
        transitionDiagnostics = diagnostics,
    )
}

private fun settingsRepository(
    transitionMode: MutableStateFlow<PanelTransitionMode>,
): ImageReaderSettingsRepository =
    Proxy.newProxyInstance(
        ImageReaderSettingsRepository::class.java.classLoader,
        arrayOf(ImageReaderSettingsRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPagedReaderReadingDirection" -> flowOf(PagedReadingDirection.LEFT_TO_RIGHT)
            "getPanelTransitionMode" -> transitionMode
            "toString" -> "PanelTransitionSettingsRepository"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ImageReaderSettingsRepository

private inline fun <reified T : Any> unsupportedProxy(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, arguments ->
        when (method.name) {
            "equals" -> proxy === arguments?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Unsupported${T::class.simpleName}"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as T
