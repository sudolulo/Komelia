package snd.komelia.ui.reader.image.panels

import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
import snd.komelia.image.PreparedTransitionCoverage
import snd.komelia.image.PreparedUpdateLease
import snd.komelia.image.ReaderImageFactory
import snd.komelia.image.ReaderImageResult
import snd.komelia.image.TransitionRenderStats
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
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
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

private val sourceRegion = IntRect(33, 33, 66, 66)

// source, zoom start, destination, swept
private val nextPanelRegions = listOf(
    sourceRegion,
    sourceRegion,
    IntRect(67, 67, 100, 100),
    IntRect(33, 33, 100, 100),
)

private val previousPanelRegions = listOf(
    sourceRegion,
    sourceRegion,
    IntRect(0, 0, 33, 33),
    IntRect(0, 0, 66, 66),
)

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
    fun `mode change during movement affects only the next transition`() = runBlocking {
        val transitionMode = MutableStateFlow(PanelTransitionMode.CLASSIC)
        val image = ControlledReaderImage(IntSize(300, 300))
        val frameClock = ControlledFrameClock()
        val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + frameClock)
        val screenScaleState = ScreenScaleState().apply {
            composeScope = animationScope
            setAreaSize(IntSize(100, 100))
            setTargetSize(IntSize(100, 100).toSize())
            setZoom(3f)
        }
        val state = panelsReaderState(screenScaleState, transitionMode)
        state.currentPage.value = PanelsReaderState.PanelsPage(
            metadata = PageMetadata(KomgaBookId("book"), 1, IntSize(300, 300)),
            imageResult = ReaderImageResult.Success(image),
            panelData = PanelsReaderState.PanelData(
                panels = listOf(
                    ImageRect(100, 100, 200, 200),
                    ImageRect(200, 200, 300, 300),
                ),
                originalImageSize = IntSize(300, 300),
                panelCoversMajorityOfImage = false,
            ),
        )

        try {
            state.initialize()
            awaitViewportObserver(state, image)

            state.nextPanel()
            withTimeout(2_000) { image.preparationStarted.await() }
            image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (state.currentPageIndex.value.panel != 1) delay(10)
            }

            frameClock.sendFrame(0L)
            frameClock.sendFrame(250_000_000L)
            delay(50)
            transitionMode.value = PanelTransitionMode.SNAPPY
            assertEquals(emptyList(), image.viewportUpdates.toList())

            frameClock.sendFrame(1_000_000_000L)
            withTimeout(2_000) {
                while (image.viewportUpdates.isEmpty()) delay(10)
            }
            val completedTransitions = image.viewportUpdates.size

            state.previousPanel()
            withTimeout(2_000) {
                while (state.currentPageIndex.value.panel != 0) delay(10)
            }
            frameClock.sendFrame(1_100_000_000L)
            frameClock.sendFrame(1_350_000_000L)
            withTimeout(2_000) {
                while (image.viewportUpdates.size == completedTransitions) delay(10)
            }
        } finally {
            state.stop()
            animationScope.cancel()
        }
    }

    @Test
    fun `real panel transition prepares corridor and suppresses animation viewport updates`() = runBlocking {
        val image = ControlledReaderImage(IntSize(300, 300))
        val frameClock = ControlledFrameClock()
        val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + frameClock)
        val screenScaleState = ScreenScaleState().apply {
            composeScope = animationScope
            setAreaSize(IntSize(100, 100))
            setTargetSize(IntSize(100, 100).toSize())
            setZoom(3f)
        }
        val state = panelsReaderState(screenScaleState)
        state.currentPage.value = PanelsReaderState.PanelsPage(
            metadata = PageMetadata(KomgaBookId("book"), 1, IntSize(300, 300)),
            imageResult = ReaderImageResult.Success(image),
            panelData = PanelsReaderState.PanelData(
                panels = listOf(
                    ImageRect(100, 100, 200, 200),
                    ImageRect(200, 200, 300, 300),
                ),
                originalImageSize = IntSize(300, 300),
                panelCoversMajorityOfImage = false,
            ),
        )

        try {
            state.initialize()
            awaitViewportObserver(state, image)
            val startTransformation = screenScaleState.transformation.value

            state.nextPanel()
            val preparation = withTimeout(2_000) { image.preparationStarted.await() }

            assertEquals(IntSize(100, 100), preparation.maxDisplaySize)
            assertRequirements(nextPanelRegions, 3f, preparation.animatedRequirements)
            assertRequirements(
                listOf(sourceRegion, IntRect(67, 67, 100, 100)),
                3f,
                preparation.endpointRequirements,
            )
            assertEquals(PanelsReaderState.PageIndex(0, 0, false), state.currentPageIndex.value)
            assertEquals(startTransformation, screenScaleState.transformation.value)

            image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (state.currentPageIndex.value.panel != 1) delay(10)
            }

            repeat(10) { frameClock.sendFrame(it * 16_000_000L) }
            delay(50)
            assertEquals(emptyList(), image.viewportUpdates.toList())

            repeat(60) { frameClock.sendFrame((it + 10) * 16_000_000L) }
            withTimeout(2_000) {
                while (image.viewportUpdates.isEmpty()) delay(10)
            }
            delay(250)

            val destinationUpdate = image.viewportUpdates.single()
            assertEquals(IntSize(100, 100), destinationUpdate.maxDisplaySize)
            assertEquals(IntRect(67, 67, 100, 100), destinationUpdate.visibleDisplaySize)
            assertEquals(3f, destinationUpdate.zoomFactor, 0.0001f)
        } finally {
            state.stop()
            animationScope.cancel()
        }
    }

    @Test
    fun `stable panel prefetches preferred next then previous`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()

            val requests = fixture.image.prefetchRequests.toList()
            assertEquals(2, requests.size)
            assertRequirements(nextPanelRegions, 3f, requests[0].requirements)
            assertRequirements(previousPanelRegions, 3f, requests[1].requirements)
            assertTrue(requests.all { it.maxDisplaySize == fixtureAreaSize })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `warm next panel renders zero tiles before movement`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()
            val rendersBeforeTransition = fixture.image.nativeRenders

            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertEquals(
                rendersBeforeTransition,
                fixture.image.nativeRenders,
                "a warm neighbour transition must render no tiles before movement",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cold next panel renders its coverage before movement`() = runBlocking {
        val fixture = panelFixture()
        try {
            // never let a prefetch complete, so the transition finds nothing warm
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            val rendersBeforeTransition = fixture.image.nativeRenders

            fixture.state.nextPanel()
            val preparation = withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertRequirements(nextPanelRegions, 3f, preparation.animatedRequirements)
            assertEquals(
                rendersBeforeTransition + preparation.animatedRequirements.toSet().size,
                fixture.image.nativeRenders,
                "a cold transition must render its complete coverage before movement",
            )
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
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
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

            // the transition prepares while the prefetch is still suspended
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `over budget animation publishes complete endpoints before transform jump`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.transitionOutcome = TransitionOutcome.ENDPOINT_ONLY
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            val startTransformation = fixture.screenScaleState.transformation.value

            fixture.state.nextPanel()
            val preparation = withTimeout(2_000) { fixture.image.preparationStarted.await() }

            assertEquals(PanelsReaderState.PageIndex(0, 1, false), fixture.state.currentPageIndex.value)
            assertEquals(startTransformation, fixture.screenScaleState.transformation.value)

            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
            // the destination offset is computed in float scale arithmetic, so it lands within
            // rounding distance of (-100, -100) rather than exactly on it
            withTimeout(2_000) {
                while (fixture.screenScaleState.transformation.value.offset.x > -99.9f) delay(10)
            }

            // both endpoints are complete, the swept path was never rendered
            assertEquals(2, preparation.endpointRequirements.size)
            assertTrue(preparation.endpointRequirements.all { fixture.image.hasCoverage(it) })
            assertFalse(fixture.image.hasCoverage(preparation.animatedRequirements.last()))

            // the destination is reached without a single animation frame, so scrollTo was not used
            assertEquals(0, fixture.frameClock.awaitedFrames)
            assertEquals(3f, fixture.screenScaleState.zoom.value, 0.0001f)
            val destination = fixture.screenScaleState.transformation.value.offset
            assertEquals(-100f, destination.x, 0.01f)
            assertEquals(-100f, destination.y, 0.01f)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `overlapping endpoints stay separate requirements in fallback`() = runBlocking {
        val fixture = panelFixture(
            panels = listOf(
                ImageRect(100, 100, 200, 200),
                ImageRect(120, 120, 220, 220),
            ),
            startPanel = 0,
        )
        try {
            fixture.image.transitionOutcome = TransitionOutcome.ENDPOINT_ONLY
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            val preparation = withTimeout(2_000) { fixture.image.preparationStarted.await() }

            val (source, destination) = preparation.endpointRequirements
            assertEquals(2, preparation.endpointRequirements.size)
            assertTrue(
                source.region.overlaps(destination.region),
                "expected overlapping endpoints, got ${source.region} and ${destination.region}",
            )
            assertNotEquals(source, destination, "overlapping endpoints must stay two requirements")
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `endpoint admission failure preserves frame index zoom and offset`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.image.transitionOutcome = TransitionOutcome.REJECTED
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()
            val startTransformation = fixture.screenScaleState.transformation.value
            val startPainter = fixture.image.painter.value

            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
            delay(300)

            assertEquals(PanelsReaderState.PageIndex(0, 1, false), fixture.state.currentPageIndex.value)
            assertEquals(startTransformation, fixture.screenScaleState.transformation.value)
            assertEquals(3f, fixture.screenScaleState.zoom.value, 0.0001f)
            assertSame(startPainter, fixture.image.painter.value)
            assertEquals(0, fixture.frameClock.awaitedFrames)
            assertEquals(emptyList(), fixture.image.viewportUpdates.toList())
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
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
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
            assertEquals(0, event.renderedTiles)
            assertTrue(event.generation > 0)
            assertTrue(event.requirementCount > 0)
            assertTrue(event.readinessMillis >= 0)
            assertTrue(event.movementEndMillis >= event.movementStartMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelled transition emits a cancellation event and no error event`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(diagnostics = { events += it })
        try {
            fixture.image.blockPrefetch()
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            // the coverage is never released, so the transition is cancelled while preparing
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

    private suspend fun awaitViewportObserver(
        state: PanelsReaderState,
        image: ControlledReaderImage,
    ) {
        withTimeout(2_000) {
            var offset = 1f
            while (image.viewportUpdates.isEmpty()) {
                state.screenScaleState.setOffset(Offset(offset, offset))
                offset = if (offset == 1f) 2f else 1f
                delay(10)
            }
        }
        delay(150)
        image.viewportUpdates.clear()
        state.screenScaleState.setOffset(Offset.Zero)
        withTimeout(2_000) {
            while (image.viewportUpdates.isEmpty()) delay(10)
        }
        delay(150)
        image.viewportUpdates.clear()
    }
}

private class ControlledFrameClock : MonotonicFrameClock {
    private val frames = Channel<Long>(Channel.UNLIMITED)
    private val requested = AtomicInteger()

    /** how many times an animation asked for a frame; zero means nothing animated at all */
    val awaitedFrames: Int get() = requested.get()

    suspend fun sendFrame(timeNanos: Long) {
        frames.send(timeNanos)
    }

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        requested.incrementAndGet()
        return onFrame(frames.receive())
    }
}

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
    diagnostics: PanelTransitionDiagnostics = PanelTransitionDiagnostics {},
): PanelFixture {
    val image = ControlledReaderImage(fixtureImageSize)
    val frameClock = ControlledFrameClock()
    val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + frameClock)
    val screenScaleState = ScreenScaleState().apply {
        composeScope = animationScope
        setAreaSize(fixtureAreaSize)
        setTargetSize(fixtureAreaSize.toSize())
        setZoom(3f)
    }
    val state = panelsReaderState(screenScaleState, diagnostics = diagnostics)
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

private data class TransitionRequest(
    val maxDisplaySize: IntSize,
    val animatedRequirements: List<ViewportRenderRequirement>,
    val endpointRequirements: List<ViewportRenderRequirement>,
)

private enum class TransitionOutcome { ANIMATED, ENDPOINT_ONLY, REJECTED }

/**
 * Models the renderer well enough to count native work: a requirement whose coverage was
 * already rendered costs nothing, anything else costs one render. Prefetch fills that coverage,
 * so a warm neighbour transition can be observed to render zero tiles.
 */
private class ControlledReaderImage(
    imageSize: IntSize,
) : ReaderImage {
    val preparationStarted = CompletableDeferred<TransitionRequest>()
    val releasePreparation = CompletableDeferred<Unit>()
    val viewportUpdates = Collections.synchronizedList(mutableListOf<ImageUpdate>())
    val prefetchRequests = CopyOnWriteArrayList<PrefetchRequest>()
    val transitionRequests = CopyOnWriteArrayList<TransitionRequest>()

    private val renderedCoverage = Collections.synchronizedSet(mutableSetOf<ViewportRenderRequirement>())
    private val renders = AtomicInteger()
    private val prefetchCancels = AtomicInteger()
    private val prefetchGate = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })

    @Volatile
    var transitionOutcome: TransitionOutcome = TransitionOutcome.ANIMATED

    @Volatile
    var prefetchFailure: Throwable? = null

    val nativeRenders: Int get() = renders.get()
    val prefetchCancellations: Int get() = prefetchCancels.get()

    fun blockPrefetch() {
        prefetchGate.set(CompletableDeferred())
    }

    fun releasePrefetch() {
        prefetchGate.get().complete(Unit)
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
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease {
        render(requirements)
        return PreparedUpdateLease {}
    }

    override suspend fun prepareTransitionUpdate(
        maxDisplaySize: IntSize,
        animatedRequirements: List<ViewportRenderRequirement>,
        endpointRequirements: List<ViewportRenderRequirement>,
    ): PreparedTransitionCoverage {
        val request = TransitionRequest(maxDisplaySize, animatedRequirements, endpointRequirements)
        transitionRequests += request
        preparationStarted.complete(request)
        releasePreparation.await()

        return when (transitionOutcome) {
            TransitionOutcome.ANIMATED ->
                PreparedTransitionCoverage.Animated(PreparedUpdateLease {}, render(animatedRequirements))

            TransitionOutcome.ENDPOINT_ONLY ->
                PreparedTransitionCoverage.EndpointOnly(PreparedUpdateLease {}, render(endpointRequirements))

            TransitionOutcome.REJECTED -> PreparedTransitionCoverage.Rejected
        }
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

    fun hasCoverage(requirement: ViewportRenderRequirement): Boolean =
        renderedCoverage.contains(requirement)

    /** one requirement stands in for one tile here, so the counts are directly assertable */
    private fun render(requirements: List<ViewportRenderRequirement>): TransitionRenderStats {
        var rendered = 0
        var reused = 0
        for (requirement in requirements.toSet()) {
            if (renderedCoverage.add(requirement)) {
                renders.incrementAndGet()
                rendered += 1
            } else {
                reused += 1
            }
        }
        return TransitionRenderStats(
            reusedTiles = reused,
            upgradedTiles = 0,
            renderedTiles = rendered,
            evictedTiles = 0,
            uniqueRetainedPixels = renderedCoverage.size.toLong(),
            reservedPixels = rendered.toLong(),
        )
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
