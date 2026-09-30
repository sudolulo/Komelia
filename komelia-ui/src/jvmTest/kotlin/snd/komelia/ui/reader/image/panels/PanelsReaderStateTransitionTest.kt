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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.withTimeoutOrNull
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
import snd.komelia.image.TilePixelBudgetExceededException
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

// five non-overlapping panels stacked through the full image height. Each is narrower than the
// page, so it fills the fixture display at panel zoom rather than fit zoom.
private val fivePanels = listOf(
    ImageRect(100, 0, 200, 60),
    ImageRect(100, 60, 200, 120),
    ImageRect(100, 120, 200, 180),
    ImageRect(100, 180, 200, 240),
    ImageRect(100, 240, 200, 300),
)

/** the fitted page in display coordinates; the retained page frame covers exactly this */
private val pageRegion = IntRect(0, 0, 100, 100)

private val sourceRegion = IntRect(33, 33, 66, 66)
private val nextDestinationRegion = IntRect(67, 67, 100, 100)
private val previousDestinationRegion = IntRect(0, 0, 33, 33)

private fun IntRect.containsRect(other: IntRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

/**
 * The invariant every panel transition owes, whichever frame it moved on: every viewport it
 * presented was drawable from the frame installed at that moment. Both frames span the whole
 * fitted page - the retained page frame, and the fit-zoom page the fallback renders - so every
 * intermediate viewport of the movement is inside the installed coverage.
 */
private fun assertEveryPresentedViewportCovered(
    presented: List<TransitionSample>,
    context: String,
    detail: String = "",
) {
    assertTrue(presented.isNotEmpty(), "$context presented no viewport at all$detail")
    assertTrue(
        presented.all { it.painterInstalled },
        "painter was null during $context at some viewport$detail",
    )
    val uncovered = presented.filterNot { sample ->
        sample.installedCoverage?.containsRect(sample.visibleRegion) == true
    }
    assertTrue(
        uncovered.isEmpty(),
        "$context presented viewports the installed frame does not cover: $uncovered$detail",
    )
}

/** output pixels the renderer is asked to produce for one requirement */
private fun renderedPixelsOf(requirement: ViewportRenderRequirement): Int =
    (requirement.region.width * requirement.zoomFactor).roundToInt() *
        (requirement.region.height * requirement.zoomFactor).roundToInt()

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
    fun `a settled mid-page panel pre-renders the fit page plus previous current and next`() = runBlocking {
        val fixture = panelFixture(panels = fivePanels, startPanel = 2)
        try {
            fixture.settle()

            val lastPrepare = fixture.image.prepareRequests.last()
            assertEquals(4, lastPrepare.requirements.size)
            val panelRequirements = lastPrepare.requirements.filter { !it.overscan }
            assertEquals(3, panelRequirements.size)
            assertTrue(panelRequirements.all { it.zoomFactor > 1f })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a tap during background preparation waits for that sharp window`() = runBlocking {
        val fixture = panelFixture(
            panels = fivePanels,
            startPanel = 2,
            pageFrameAvailable = false,
        )
        try {
            fixture.awaitViewportObserver()
            assertTrue(fixture.state.prepareRollingWindow())
            val backgroundPreparation = withTimeout(2_000) {
                fixture.image.preparationStarted.await()
            }
            assertEquals(4, backgroundPreparation.requirements.size)
            val rendersBeforePreparation = fixture.image.nativeRenders

            fixture.state.nextPanel()
            delay(100)

            assertEquals(
                1,
                fixture.image.prepareRequests.size,
                "the transition must join the in-flight background preparation",
            )
            assertEquals(
                PanelsReaderState.PageIndex(0, 2, false),
                fixture.state.currentPageIndex.value,
                "movement must wait for sharp coverage",
            )
            assertEquals(
                rendersBeforePreparation,
                fixture.image.nativeRenders,
                "the blocked preparation must not expose a soft render",
            )

            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 3) delay(10)
            }

            assertEquals(
                2,
                fixture.image.prepareRequests.size,
                "movement must reacquire the completed rolling window exactly once",
            )
            assertEquals(
                rendersBeforePreparation + 4,
                fixture.image.nativeRenders,
                "reacquiring the completed window must perform no additional native renders",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `rapid input before the edge is ready waits for sharp coverage`() = runBlocking {
        val fixture = panelFixture(startPanel = 0, pageFrameAvailable = false)
        try {
            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.preparationStarted.await() }

            assertFalse(fixture.movementStarted())

            fixture.image.releasePreparation.complete(Unit)
            fixture.runMovementToCompletion()

            assertEquals(
                PanelTransitionFrameSource.ROLLING_WINDOW,
                fixture.lastEvent().frameSource,
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `readiness reports the wait when the window was not pre-rendered`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(startPanel = 0, pageFrameAvailable = false)
        try {
            fixture.withDiagnostics { events += it }

            fixture.state.nextPanel()
            fixture.delayPreparation(millis = 40)
            fixture.runMovementToCompletion()

            assertTrue(events.last().readinessMillis >= 40)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an over budget window falls back to a sharp current and destination pair`() = runBlocking {
        val fixture = panelFixture(startPanel = 1)
        try {
            fixture.image.failNextRollingWindowAsOverBudget()

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.image.prepareRequests.size < 2) delay(10)
            }
            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (!fixture.movementStarted()) delay(10)
            }
            fixture.runMovementToCompletion()

            val event = fixture.lastEvent()
            assertEquals(PanelTransitionFrameSource.SHARP_PAIR_FALLBACK, event.frameSource)
            val fallback = fixture.image.prepareRequests[1]
            assertEquals(3, fallback.requirements.size)
            assertEquals(2, fallback.requirements.count { !it.overscan && it.zoomFactor > 1f })
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a material resize rebuilds the window around the same panel and never reuses old coordinates`() =
        runBlocking {
            val fixture = panelFixture(startPanel = 1)
            try {
                fixture.settle()
                val oldPanelRegions = fixture.image.prepareRequests.last().requirements
                    .filterNot { it.overscan }
                    .map { it.region }

                fixture.resizeArea(IntSize(2208, 1840))
                fixture.settle()

                val lastPrepare = fixture.image.prepareRequests.last()
                assertEquals(IntSize(2208, 1840), lastPrepare.maxDisplaySize)
                assertEquals(1, fixture.state.currentPageIndex.value.panel)
                assertNotEquals(
                    oldPanelRegions,
                    lastPrepare.requirements.filterNot { it.overscan }.map { it.region },
                    "the resized window must use coordinates calculated for the new display geometry",
                )
            } finally {
                fixture.close()
            }
        }

    @Test
    fun `input immediately after an area change does not prepare with stale geometry`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(
            startPanel = 1,
            pageFrameAvailable = false,
            diagnostics = { events += it },
        )
        try {
            fixture.awaitViewportObserver()

            // Put the resize collector inside its throttle window without changing the fitted
            // geometry. Waiting for the blocked transition's cancellation proves the collector
            // handled this first area change before the real resize and input below.
            fixture.state.nextPanel()
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            val page = fixture.state.currentPage.value!!
            fixture.state.currentPage.value = page.copy(panelData = null)
            fixture.screenScaleState.setAreaSize(IntSize(150, 150))
            withTimeout(2_000) {
                while (events.none { it.outcome == PanelTransitionOutcome.CANCELLED }) delay(10)
            }

            fixture.state.currentPage.value = page
            val resizedArea = IntSize(200, 200)
            fixture.screenScaleState.setAreaSize(resizedArea)
            fixture.state.nextPanel()

            withTimeout(2_000) {
                while (
                    fixture.image.prepareRequests.none { request ->
                        request.maxDisplaySize == resizedArea &&
                            request.requirements.single { it.overscan }.region == IntRect(0, 0, 200, 200)
                    }
                ) {
                    delay(10)
                }
            }

            val resizedRequests = fixture.image.prepareRequests.filter { it.maxDisplaySize == resizedArea }
            assertTrue(
                resizedRequests.all { request ->
                    request.requirements.single { it.overscan }.region == IntRect(0, 0, 200, 200)
                },
                "input during resize must not prepare new-area frames from the old target geometry: " +
                    resizedRequests,
            )
            assertEquals(
                1,
                fixture.state.currentPageIndex.value.panel,
                "input must wait while resize rebuilds the current panel geometry",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a warm forward transition renders nothing`() = runBlocking {
        val fixture = panelFixture(panels = fivePanels, startPanel = 2)
        try {
            fixture.settle()
            val rendersBefore = fixture.image.nativeRenders

            fixture.state.nextPanel()
            fixture.runMovementToCompletion()

            assertEquals(rendersBefore, fixture.image.nativeRenders)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a warm backward transition renders nothing`() = runBlocking {
        val fixture = panelFixture(panels = fivePanels, startPanel = 2)
        try {
            fixture.settle()
            val rendersBefore = fixture.image.nativeRenders

            fixture.state.previousPanel()
            fixture.runMovementToCompletion()

            assertEquals(rendersBefore, fixture.image.nativeRenders)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `after arrival only the newly entering edge panel is rendered`() = runBlocking {
        val fixture = panelFixture(panels = fivePanels, startPanel = 2)
        try {
            fixture.settle()

            fixture.state.nextPanel()
            fixture.runMovementToCompletion()
            val rendersBefore = fixture.image.nativeRenders
            fixture.settle()

            assertEquals(rendersBefore + 1, fixture.image.nativeRenders)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `painter is never null during a panel transition`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()

            val recording = recordPresentations(fixture)

            fixture.state.nextPanel()
            fixture.runMovementToCompletion()
            recording.stop()

            assertTrue(
                recording.samples.any { it.visibleRegion != sourceRegion },
                "the transition must have moved the viewport, sampled " +
                    "${recording.samples.map { it.visibleRegion }}",
            )
            assertEveryPresentedViewportCovered(
                recording.samples,
                "a transition on the rolling window",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `transition prepares the rolling window before changing zoom or offset`() = runBlocking {
        val fixture = panelFixture(pageFrameAvailable = false)
        try {
            fixture.awaitViewportObserver()
            val startTransformation = fixture.screenScaleState.transformation.value

            fixture.state.nextPanel()
            val preparation = withTimeout(2_000) { fixture.image.preparationStarted.await() }

            assertEquals(PanelsReaderState.PageIndex(0, 1, false), fixture.state.currentPageIndex.value)
            assertEquals(startTransformation, fixture.screenScaleState.transformation.value)
            assertEquals(4, preparation.requirements.size)
            assertEquals(3, preparation.requirements.count { !it.overscan })

            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }

            assertEquals(listOf(Publication.TILED), fixture.image.publications.toList())
            assertEquals(pageRegion, fixture.image.installedCoverage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `transition performs zero native renders before movement starts`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()
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
    fun `arrival replaces the movement window only after the new edge is ready`() = runBlocking {
        val fixture = panelFixture(panels = fivePanels, startPanel = 2)
        try {
            fixture.settle()

            fixture.state.nextPanel()
            fixture.runMovementToCompletion()

            // The shifted window is blocked on its missing edge, so only the reacquired movement
            // window has published and no ordinary viewport update has replaced it.
            assertEquals(emptyList(), fixture.image.viewportUpdates.toList())
            assertEquals(listOf(Publication.TILED), fixture.image.publications.toList())

            fixture.settle()

            assertEquals(
                listOf(Publication.TILED, Publication.TILED),
                fixture.image.publications.toList(),
                "the shifted rolling window must publish only after its new edge is ready",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `over budget post arrival window waits for the selected destination`() = runBlocking {
        val fixture = panelFixture(panels = fivePanels, startPanel = 2)
        try {
            fixture.settle()

            fixture.image.failRollingWindowsAsOverBudget()
            val requestsBeforeTransition = fixture.image.prepareRequests.size
            fixture.state.nextPanel()
            fixture.runMovementToCompletion()
            fixture.settle()

            assertEquals(
                requestsBeforeTransition + 3,
                fixture.image.prepareRequests.size,
                "an over-budget background window must not guess a fallback destination before input",
            )

            val requestsBeforeInput = fixture.image.prepareRequests.size
            fixture.state.previousPanel()
            fixture.runMovementToCompletion()

            val fallbackPair = fixture.image.prepareRequests[requestsBeforeInput + 1]
            val sharpRegions = fallbackPair.requirements.filterNot { it.overscan }.map { it.region }

            assertEquals(
                listOf(
                    IntRect(left = 33, top = 53, right = 66, bottom = 86),
                    IntRect(left = 33, top = 33, right = 66, bottom = 66),
                ),
                sharpRegions,
                "the fallback must contain the current panel and the destination selected by input",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `an ordinary requestUpdate mid animation does not publish over the rolling window`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()

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
                "no ordinary viewport update may be issued while the rolling-window lease is held",
            )
            assertEquals(pageRegion, fixture.image.installedCoverage)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelling mid animation leaves the rolling window published and releases ownership once`() = runBlocking {
        val fixture = panelFixture()
        try {
            fixture.settle()
            val releasesBefore = fixture.image.preparedLeaseReleases

            val recording = recordPresentations(fixture)

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
            recording.stop()

            // the rolling window stayed installed for every swept viewport, cancellation included
            assertEveryPresentedViewportCovered(recording.samples, "a cancelled transition")

            assertEquals(
                fixture.image.prepareRequests.size,
                fixture.image.preparedLeaseReleases,
                "every completed rolling-window preparation must release its lease exactly once; " +
                    "started with $releasesBefore releases",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a transition before the rolling window is ready waits for sharp coverage`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(pageFrameAvailable = false, diagnostics = { events += it })
        try {
            fixture.awaitViewportObserver()
            val startTransformation = fixture.screenScaleState.transformation.value

            fixture.state.nextPanel()
            val fallback = withTimeout(2_000) { fixture.image.preparationStarted.await() }

            assertEquals(fixtureAreaSize, fallback.maxDisplaySize)
            assertEquals(4, fallback.requirements.size)
            assertEquals(1, fallback.requirements.count { it.overscan })
            assertEquals(3, fallback.requirements.count { !it.overscan })
            assertEquals(startTransformation, fixture.screenScaleState.transformation.value)
            assertEquals(PanelsReaderState.PageIndex(0, 1, false), fixture.state.currentPageIndex.value)

            fixture.image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            withTimeout(2_000) {
                while (events.none { it.outcome == PanelTransitionOutcome.ANIMATED }) delay(10)
            }
            withTimeout(2_000) {
                while (fixture.image.publications.size < 2) delay(10)
            }

            assertEquals(
                listOf(Publication.TILED, Publication.TILED),
                fixture.image.publications.toList(),
                "the transition publishes its sharp window, then the post-arrival window",
            )
            assertEquals(0, fixture.image.pageFramePublications)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `the rolling window includes whole-page fit coverage with exact sharp panels`() = runBlocking {
        val fixture = panelFixture(pageFrameAvailable = false)
        try {
            fixture.awaitViewportObserver()

            fixture.state.nextPanel()
            val fallback = withTimeout(2_000) { fixture.image.preparationStarted.await() }

            val requirement = fallback.requirements.single { it.overscan }
            assertEquals(pageRegion, requirement.region, "the fallback must cover the whole fitted page")
            assertEquals(1f, requirement.zoomFactor, 0.0001f, "the fallback must render at fit zoom")
            assertEquals(3, fallback.requirements.count { !it.overscan })

            // the corridor this replaced was the bounding box of the post-zoom start and the
            // destination, rendered at the panel zoom the movement ends on. It covers less of the
            // page and still costs several times the output pixels, because zoom is squared.
            val corridor = ViewportRenderRequirement(IntRect(33, 33, 100, 100), 3f)
            assertEquals(10_000, renderedPixelsOf(requirement))
            assertEquals(40_401, renderedPixelsOf(corridor))
            assertTrue(
                renderedPixelsOf(requirement) < renderedPixelsOf(corridor),
                "the fit-zoom page must cost fewer output pixels than the corridor it replaced",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a cold transition animates across a rendered rolling window`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(pageFrameAvailable = false, diagnostics = { events += it })
        try {
            fixture.awaitViewportObserver()
            fixture.image.releasePreparation.complete(Unit)
            assertEquals(sourceRegion, fixture.image.installedCoverage)

            val recording = recordPresentations(fixture)
            // the recorder samples the current transformation first; the transition waits for
            // that sample, so every later sample comes from a change the transition itself made
            withTimeout(2_000) {
                while (recording.samples.isEmpty()) delay(10)
            }

            fixture.state.nextPanel()
            withTimeout(2_000) {
                while (fixture.state.currentPageIndex.value.panel != 2) delay(10)
            }
            // enough frames to carry a 250 ms animation to completion, had one been started
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            fixture.awaitStableTransformation()
            delay(150)
            recording.stop()

            assertEveryPresentedViewportCovered(
                presented = recording.samples,
                context = "a transition with no page frame",
                detail = "; transitions=$events prepares=${fixture.image.prepareRequests}",
            )
            val rollingWindowCoverage = pageRegion
            val rollingWindowSamples = recording.samples.filter { it.installedCoverage == rollingWindowCoverage }
            assertTrue(
                rollingWindowSamples.map { it.visibleRegion }.distinct().size > 1,
                "the driven frame clock must expose animated rolling-window movement, sampled " +
                    "${rollingWindowSamples.map { it.visibleRegion }}",
            )
            val firstPrepare = fixture.image.prepareRequests.first()
            assertEquals(1, firstPrepare.requirements.count { it.overscan })
            assertEquals(3, firstPrepare.requirements.count { !it.overscan })
            assertEquals(2, fixture.state.currentPageIndex.value.panel)
            assertEquals(3f, fixture.screenScaleState.transformation.value.scale, 0.0001f)

            val event = events.single()
            assertEquals(PanelTransitionOutcome.ANIMATED, event.outcome)
            assertEquals(PanelTransitionFrameSource.ROLLING_WINDOW, event.frameSource)
            assertEquals(
                250,
                event.durationMillis,
                "the fallback must report the selected mode's full duration: ${event.toLogLine()}",
            )
        } finally {
            fixture.close()
        }
    }

    /**
     * What the coverage assertion is allowed to conclude rests entirely on this: a viewport belongs
     * to the frame that was installed while it was on screen. Reading the frame later attributes
     * the viewport to whatever has been installed since - and a transition ends by installing a
     * destination-only frame on the ordinary viewport path, so a viewport from just before arrival
     * read afterwards looks like a transition that swept space its frame did not cover, when the
     * frame that was actually installed under it was the whole page.
     */
    @Test
    fun `a viewport is recorded against the frame installed while it was on screen`() = runBlocking {
        val screenScaleState = ScreenScaleState().apply {
            setAreaSize(fixtureAreaSize)
            setTargetSize(fixtureAreaSize.toSize())
            setZoom(3f)
        }
        val image = ControlledReaderImage(fixtureImageSize, true)
        // the whole fitted page: the coverage a transition animates on
        image.requestUpdate(fixtureAreaSize, 1f, pageRegion)
        val recording = recordPresentations(screenScaleState, image)
        try {
            withTimeout(2_000) {
                while (recording.samples.isEmpty()) delay(10)
            }

            screenScaleState.setOffset(Offset(-1f, -1f))
            // read before the publication's caller can do anything else, installing a frame
            // included: a pair the recorder only assembles later is a pair it can assemble after
            // the next installation, and that is exactly how a viewport gets misattributed
            val recordedAtPublication = recording.samples.toList()
            val published = visibleDisplayRegion(
                displaySize = screenScaleState.targetSize.value.toIntSize(),
                areaSize = screenScaleState.areaSize.value,
                transformation = screenScaleState.transformation.value,
            )

            assertFalse(
                nextDestinationRegion.containsRect(published),
                "the viewport must be one a destination-only frame does not cover, got $published",
            )
            assertEquals(
                listOf(pageRegion),
                recordedAtPublication
                    .filter { it.visibleRegion == published }
                    .map { it.installedCoverage },
                "$published must be recorded against the frame installed under it by the time its " +
                    "publication returns, recorded $recordedAtPublication",
            )
        } finally {
            recording.stop()
        }
    }

    @Test
    fun `snappy requests 250ms and classic requests 1000ms, snapshotted at input`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val transitionMode = MutableStateFlow(PanelTransitionMode.CLASSIC)
        val fixture = panelFixture(transitionMode = transitionMode, diagnostics = { events += it })
        try {
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
            assertEquals(PanelTransitionFrameSource.ROLLING_WINDOW, event.frameSource)
            assertTrue(event.generation > 0)
            assertTrue(event.readinessMillis >= 0)
            assertTrue(event.movementEndMillis >= event.movementStartMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a warm rolling transition reports its measured readiness`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(
            pageFrameAvailable = true,
            diagnostics = PanelTransitionDiagnostics { events += it },
        )
        try {
            fixture.settle()
            fixture.state.nextPanel()
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            withTimeout(2_000) { while (events.isEmpty()) delay(10) }

            assertEquals(1, events.size, "one input must emit exactly one event")
            val event = events.single()
            assertEquals(PanelTransitionFrameSource.ROLLING_WINDOW, event.frameSource)
            assertTrue(event.readinessMillis >= 0L)
            assertEquals(PanelTransitionMode.SNAPPY, event.mode)
            assertEquals(250, event.durationMillis)
            assertTrue(
                "stats" !in event.toLogLine(),
                "the log line must not carry a field it can never populate: ${event.toLogLine()}",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `cancelled transition emits a cancellation event and no error event`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(pageFrameAvailable = false, diagnostics = { events += it })
        try {
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
            assertEquals(250, event.durationMillis)
            assertTrue(
                events.none { it.outcome == PanelTransitionOutcome.FAILED },
                "a cancellation must never be reported as a renderer failure, got $events",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a page with panels prepares at fit zoom so the full resize path retains a page frame`() = runBlocking {
        val fixture = panelFixture()
        try {
            val containerSize = fixture.screenScaleState.areaSize.value
            val page = fixture.state.currentPage.value!!
            val withPanels = fixture.state.getScaleFor(page, containerSize)

            val fitted = ScreenScaleState()
            fitted.setAreaSize(containerSize)
            fitted.setTargetSize(fixture.screenScaleState.targetSize.value)
            fitted.enableOverscrollArea(true)
            fitted.setZoom(0f)

            assertEquals(
                fitted.zoom.value,
                withPanels.zoom.value,
                "a page with panels must prepare at fit zoom, not panel-fit zoom",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a freshly published page animates into its first panel`() = runBlocking {
        val fixture = panelFixture(startPanel = 0)
        try {
            fixture.awaitViewportObserver()
            fixture.screenScaleState.setZoom(0f)
            val fitTransformation = fixture.screenScaleState.transformation.value

            assertTrue(fixture.state.enterFirstPanel(), "entering the first panel must start a transition")

            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            withTimeout(2_000) {
                while (fixture.screenScaleState.transformation.value == fitTransformation) delay(10)
            }

            assertNotEquals(
                fitTransformation,
                fixture.screenScaleState.transformation.value,
                "the viewport must have moved off fit zoom into panel 0",
            )
            assertEquals(0, fixture.state.currentPageIndex.value.panel)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a material area change re-enters the current panel at the new geometry`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(startPanel = 1, diagnostics = { events += it })
        try {
            fixture.awaitViewportObserver()

            fixture.screenScaleState.setAreaSize(IntSize(200, 200))
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            val moved = withTimeoutOrNull(2_000) {
                while (fixture.screenScaleState.transformation.value.scale == 1f) delay(10)
                true
            }
            assertTrue(
                moved == true,
                "the re-entry never moved into the panel; transitions=$events " +
                    "prepares=${fixture.image.prepareRequests}",
            )

            assertEquals(
                1,
                fixture.state.currentPageIndex.value.panel,
                "a resize must leave the reader on the panel it was reading",
            )
            assertEquals(IntSize(200, 200), fixture.screenScaleState.areaSize.value)
            assertEquals(IntSize(200, 200).toSize(), fixture.screenScaleState.targetSize.value)

            assertEquals(
                0,
                fixture.image.pageFramePublications,
                "a page frame retained for the previous page size must never be published at the new one",
            )
            val rollingWindow = fixture.image.prepareRequests.last()
            assertEquals(IntSize(200, 200), rollingWindow.maxDisplaySize)
            assertEquals(IntRect(0, 0, 200, 200), rollingWindow.requirements.single { it.overscan }.region)
            assertEquals(3, rollingWindow.requirements.count { !it.overscan })
            assertTrue(
                fixture.image.viewportUpdates.any { it.maxDisplaySize == IntSize(200, 200) },
                "the new fit geometry must go out on the ordinary viewport path, " +
                    "got ${fixture.image.viewportUpdates}",
            )

            withTimeout(2_000) {
                var previousPrepareCount = -1
                var stableChecks = 0
                while (stableChecks < 3) {
                    delay(10)
                    val prepareCount = fixture.image.prepareRequests.size
                    val allReleased = fixture.image.preparedLeaseReleases == prepareCount
                    val movementFinished = events.any { it.outcome == PanelTransitionOutcome.ANIMATED }
                    if (movementFinished && allReleased && prepareCount == previousPrepareCount) {
                        stableChecks++
                    } else {
                        stableChecks = 0
                    }
                    previousPrepareCount = prepareCount
                }
            }
            assertEquals(
                fixture.image.prepareRequests.size,
                fixture.image.preparedLeaseReleases,
                "every lease the re-entry took must be released exactly once",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a post resize re-entry keeps every swept viewport covered`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(startPanel = 0, diagnostics = { events += it })
        try {
            fixture.awaitViewportObserver()
            // the reader is genuinely reading panel 0 before the resize: panel 0 of a 300x300
            // page fitted into 100x100 sits at offset (100, 100) at panel zoom, and the frame
            // installed for it covers that panel's viewport rather than the whole page
            fixture.screenScaleState.setOffset(Offset(100f, 100f))
            withTimeout(2_000) {
                while (fixture.image.viewportUpdates.isEmpty()) delay(10)
            }
            delay(150)
            assertEquals(previousDestinationRegion, fixture.image.installedCoverage)

            val recording = recordPresentations(fixture)
            // the recorder samples the current transformation first; the resize waits for that
            // sample so no sample can pair a pre-resize transformation with the new area size
            withTimeout(2_000) {
                while (recording.samples.isEmpty()) delay(10)
            }

            fixture.screenScaleState.setAreaSize(IntSize(200, 200))
            withTimeout(2_000) { fixture.image.preparationStarted.await() }
            fixture.image.releasePreparation.complete(Unit)
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            val moved = withTimeoutOrNull(2_000) {
                while (fixture.screenScaleState.transformation.value.offset == Offset.Zero) delay(10)
                true
            }
            assertTrue(
                moved == true,
                "the re-entry never moved into the panel; transitions=$events " +
                    "prepares=${fixture.image.prepareRequests}",
            )
            fixture.awaitStableTransformation()
            delay(150)
            recording.stop()

            // The movement runs entirely at the panel zoom, which setZoom installs before the
            // scroll animation starts. Everything up to and including the single fit-scale
            // sample is the resize applying its new page geometry, before the transition exists.
            val samples = recording.samples
            val resizeApplied = samples.indexOfFirst { it.scale == 1f }
            assertTrue(
                resizeApplied >= 0,
                "the resize must have applied its fit geometry, sampled $samples",
            )
            val movement = samples.drop(resizeApplied + 1)
            assertEveryPresentedViewportCovered(
                presented = movement,
                context = "the post-resize re-entry",
                detail = "; transitions=$events prepares=${fixture.image.prepareRequests}",
            )
            assertTrue(
                movement.map { it.visibleRegion }.distinct().size > 1,
                "the post-resize fallback must animate through multiple viewports; " +
                    "samples=${movement.map { it.visibleRegion }} transitions=$events",
            )
            val rollingWindow = fixture.image.prepareRequests.last()
            assertEquals(IntRect(0, 0, 200, 200), rollingWindow.requirements.single { it.overscan }.region)
            assertEquals(2, rollingWindow.requirements.count { !it.overscan })
            assertEquals(PanelTransitionOutcome.ANIMATED, events.single().outcome)
            assertEquals(250, events.single().durationMillis)
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a burst of area changes prepares a bounded number of times and honours the final size`() = runBlocking {
        val fixture = panelFixture(startPanel = 1)
        try {
            fixture.awaitViewportObserver()

            // a window resize drag: twenty intermediate sizes, spaced far enough apart that an
            // unthrottled collector prepares every single one of them
            repeat(20) { step ->
                fixture.screenScaleState.setAreaSize(IntSize(200 + step * 5, 200 + step * 5))
                delay(10)
            }
            delay(300)

            assertTrue(
                fixture.image.prepareRequests.map { it.maxDisplaySize }.distinct().size <= 3,
                "a resize drag must not prepare one frame per intermediate size, prepared " +
                    "${fixture.image.prepareRequests.map { it.maxDisplaySize }}",
            )
            assertEquals(
                IntSize(295, 295),
                fixture.image.prepareRequests.last().maxDisplaySize,
                "the size the drag ended on must always be prepared",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a newer area change wins and the superseded one never moves the viewport`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(startPanel = 1, diagnostics = { events += it })
        try {
            fixture.awaitViewportObserver()

            val recording = recordPresentations(fixture)

            fixture.screenScaleState.setAreaSize(IntSize(200, 200))
            withTimeout(2_000) {
                while (fixture.image.prepareRequests.isEmpty()) delay(10)
            }

            fixture.screenScaleState.setAreaSize(IntSize(250, 250))
            withTimeout(2_000) {
                while (fixture.image.prepareRequests.none { it.maxDisplaySize == IntSize(250, 250) }) delay(10)
            }

            assertEquals(
                listOf(IntSize(200, 200), IntSize(250, 250)),
                fixture.image.prepareRequests.map { it.maxDisplaySize }.distinct(),
                "the newer area must prepare for itself instead of inheriting the superseded one",
            )

            fixture.image.releasePreparation.complete(Unit)
            repeat(70) { fixture.frameClock.sendFrame(it * 16_000_000L) }
            withTimeout(2_000) {
                while (fixture.screenScaleState.transformation.value.scale == 1f) delay(10)
            }
            withTimeout(2_000) {
                while (events.none { it.outcome == PanelTransitionOutcome.ANIMATED }) delay(10)
            }
            delay(150)
            recording.stop()

            assertEquals(IntSize(250, 250).toSize(), fixture.screenScaleState.targetSize.value)
            assertEquals(1, fixture.state.currentPageIndex.value.panel)
            assertEquals(
                1,
                events.count { it.outcome == PanelTransitionOutcome.ANIMATED },
                "only the latest resize may move the viewport, got $events",
            )
            assertTrue(
                events.any { it.outcome != PanelTransitionOutcome.ANIMATED },
                "the superseded resize must reach a terminal outcome without moving, got $events",
            )
            assertTrue(
                events.all { it.durationMillis == 250 },
                "every terminal outcome must report the selected mode's duration, got $events",
            )
            val installedFallback = recording.samples.filter {
                it.installedCoverage == IntRect(0, 0, 250, 250)
            }
            assertEveryPresentedViewportCovered(
                presented = installedFallback,
                context = "the winning resize transition",
                detail = "; transitions=$events prepares=${fixture.image.prepareRequests}",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a second resize cancels a gated old size rolling window before it can publish`() = runBlocking {
        val events = CopyOnWriteArrayList<PanelTransitionEvent>()
        val fixture = panelFixture(
            startPanel = 1,
            pageFrameAvailable = false,
            diagnostics = { events += it },
        )
        try {
            fixture.awaitViewportObserver()

            val oldArea = IntSize(200, 200)
            fixture.screenScaleState.setAreaSize(oldArea)
            withTimeout(2_000) {
                while (fixture.image.prepareRequests.none { it.maxDisplaySize == oldArea }) delay(10)
            }
            // Let the resize collector leave its throttle window while the old rolling window
            // remains gated inside prepareUpdate.
            delay(150)
            val rendersBeforeRelease = fixture.image.nativeRenders

            // The replacement page cannot prepare another window. The resize itself must still
            // invalidate the old one rather than relying on a later prepareRollingWindow call.
            val page = fixture.state.currentPage.value!!
            fixture.state.currentPage.value = page.copy(panelData = null)
            fixture.screenScaleState.setAreaSize(IntSize(250, 250))
            withTimeout(2_000) {
                while (events.none { it.outcome == PanelTransitionOutcome.CANCELLED }) delay(10)
            }

            fixture.image.releasePreparation.complete(Unit)
            delay(150)

            assertEquals(
                rendersBeforeRelease,
                fixture.image.nativeRenders,
                "the superseded $oldArea rolling window rendered after the new area became active",
            )
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a page with no panels does not try to enter a panel`() = runBlocking {
        val fixture = panelFixture(panels = emptyList())
        try {
            fixture.awaitViewportObserver()
            assertTrue(!fixture.state.enterFirstPanel(), "a page with no panels must not start a transition")
        } finally {
            fixture.close()
        }
    }

    /**
     * A page fitted down to a 2x2 display is degenerate: at the panel zoom the destination
     * viewport rounds away to an empty region, so there is no renderable view to arrive on and
     * the reader must stay where it is rather than animate onto nothing.
     */
    @Test
    fun `a destination viewport that is not renderable does not start a transition`() = runBlocking {
        val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val screenScaleState = ScreenScaleState().apply {
            composeScope = animationScope
            setAreaSize(fixtureAreaSize)
            setTargetSize(Size(2f, 2f))
        }
        val state = panelsReaderState(screenScaleState)
        try {
            state.currentPage.value = PanelsReaderState.PanelsPage(
                metadata = PageMetadata(KomgaBookId("book"), 1, fixtureImageSize),
                imageResult = ReaderImageResult.Success(ControlledReaderImage(fixtureImageSize, true)),
                panelData = PanelsReaderState.PanelData(
                    panels = listOf(ImageRect(0, 0, 60, 60)),
                    originalImageSize = fixtureImageSize,
                    panelCoversMajorityOfImage = false,
                ),
            )
            state.currentPageIndex.value = PanelsReaderState.PageIndex(0, 0, false)
            val startTransformation = screenScaleState.transformation.value

            assertFalse(
                state.enterFirstPanel(),
                "a destination viewport that is not renderable must not start a transition",
            )
            assertEquals(
                startTransformation,
                screenScaleState.transformation.value,
                "a refused transition must leave the viewport where it was",
            )
        } finally {
            state.stop()
            animationScope.cancel()
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
    val scale: Float,
    val painterInstalled: Boolean,
    val installedCoverage: IntRect?,
)

/**
 * Records what the reader presented: one [TransitionSample] per viewport the scale state publishes
 * and one per frame the renderer installs, in the order they happened.
 *
 * Both sides are appended by the thread that changed them, while the other side is held, so every
 * entry is a (viewport, installed frame) pair that really held at one instant. That is the whole
 * point: sampling the two sides independently pairs a viewport with a frame that was installed
 * after it, and a transition ends by installing a destination-only frame on the ordinary viewport
 * path, so a viewport from just before arrival read afterwards looks like a transition that swept
 * space its frame did not cover when the frame installed under it was the whole page.
 */
private class PresentationRecording(
    private val screenScaleState: ScreenScaleState,
    private val image: ControlledReaderImage,
) {
    val samples = CopyOnWriteArrayList<TransitionSample>()

    private var collector: Job? = null

    fun start(scope: CoroutineScope) {
        image.frameInstalled = { record() }
        // Unconfined on purpose, and not an optimisation: a dispatched collector resumes some time
        // after the viewport was published and reads whatever frame is installed by then, which is
        // the misattribution above. Unconfined collection runs the append inside the publication
        // itself, on the thread that published it, so nothing can install in between.
        collector = scope.launch(Dispatchers.Unconfined) {
            try {
                screenScaleState.transformation.collect { record() }
            } finally {
                image.frameInstalled = null
            }
        }
    }

    suspend fun stop() {
        collector?.cancelAndJoin()
    }

    private fun record() = image.withInstalledFrameHeld { coverage, painter ->
        val transformation = screenScaleState.transformation.value
        samples += TransitionSample(
            visibleRegion = visibleDisplayRegion(
                displaySize = screenScaleState.targetSize.value.toIntSize(),
                areaSize = screenScaleState.areaSize.value,
                transformation = transformation,
            ),
            scale = transformation.scale,
            painterInstalled = painter != null,
            installedCoverage = coverage,
        )
    }
}

private fun CoroutineScope.recordPresentations(
    screenScaleState: ScreenScaleState,
    image: ControlledReaderImage,
): PresentationRecording =
    PresentationRecording(screenScaleState, image).also { it.start(this) }

private fun CoroutineScope.recordPresentations(fixture: PanelFixture): PresentationRecording =
    recordPresentations(fixture.screenScaleState, fixture.image)

private class PanelFixture(
    val state: PanelsReaderState,
    val image: ControlledReaderImage,
    val screenScaleState: ScreenScaleState,
    val frameClock: ControlledFrameClock,
    private val animationScope: CoroutineScope,
    private val initialPanel: Int,
    private val events: CopyOnWriteArrayList<PanelTransitionEvent>,
    private val diagnosticListeners: CopyOnWriteArrayList<(PanelTransitionEvent) -> Unit>,
) {
    private var viewportObserverSettled = false

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

    /** Leaves the reader on a stable panel with its viewport observers settled. */
    suspend fun settle() {
        if (image.prepareRequests.isEmpty()) state.prepareRollingWindow()
        if (!viewportObserverSettled) {
            awaitViewportObserver()
            viewportObserverSettled = true
        }
        image.releasePreparation.complete(Unit)
        var previousPrepareCount = -1
        var stableChecks = 0
        withTimeout(2_000) {
            while (stableChecks < 3) {
                repeat(70) { frameClock.sendFrame(it * 16_000_000L) }
                delay(25)
                val currentPrepareCount = image.prepareRequests.size
                if (currentPrepareCount == previousPrepareCount) stableChecks++ else stableChecks = 0
                previousPrepareCount = currentPrepareCount
            }
        }
    }

    fun movementStarted(): Boolean = state.currentPageIndex.value.panel != initialPanel

    suspend fun delayPreparation(millis: Long) {
        withTimeout(2_000) { image.preparationStarted.await() }
        delay(millis)
        image.releasePreparation.complete(Unit)
    }

    suspend fun resizeArea(areaSize: IntSize) {
        val existingRequests = image.prepareRequests.size
        screenScaleState.setAreaSize(areaSize)
        withTimeout(2_000) {
            while (image.prepareRequests.drop(existingRequests).none { it.maxDisplaySize == areaSize }) {
                delay(10)
            }
        }
    }

    fun withDiagnostics(listener: (PanelTransitionEvent) -> Unit) {
        diagnosticListeners += listener
    }

    suspend fun lastEvent(): PanelTransitionEvent = withTimeout(2_000) {
        while (events.isEmpty()) delay(10)
        events.last()
    }

    /** Drives the controlled frame clock past the longest panel movement and waits for stability. */
    suspend fun runMovementToCompletion() {
        image.blockPreparation()
        repeat(70) { frameClock.sendFrame(it * 16_000_000L) }
        awaitStableTransformation()
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
    val events = CopyOnWriteArrayList<PanelTransitionEvent>()
    val diagnosticListeners = CopyOnWriteArrayList<(PanelTransitionEvent) -> Unit>()
    val screenScaleState = ScreenScaleState().apply {
        composeScope = animationScope
        setAreaSize(fixtureAreaSize)
        setTargetSize(fixtureAreaSize.toSize())
        setZoom(3f)
    }
    val state = panelsReaderState(
        screenScaleState,
        transitionMode,
        PanelTransitionDiagnostics { event ->
            events += event
            diagnostics.onTransition(event)
            diagnosticListeners.forEach { it(event) }
        },
    )
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
    return PanelFixture(
        state = state,
        image = image,
        screenScaleState = screenScaleState,
        frameClock = frameClock,
        animationScope = animationScope,
        initialPanel = startPanel,
        events = events,
        diagnosticListeners = diagnosticListeners,
    )
}

private data class ImageUpdate(
    val maxDisplaySize: IntSize,
    val zoomFactor: Float,
    val visibleDisplaySize: IntRect,
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
    val pageFramePublicationStarted = CompletableDeferred<Unit>()

    private val preparationGate = AtomicReference(
        CompletableDeferred<Unit>().apply {
            if (pageFrameAvailable) complete(Unit)
        }
    )

    val releasePreparation: CompletableDeferred<Unit>
        get() = preparationGate.get()

    val viewportUpdates = Collections.synchronizedList(mutableListOf<ImageUpdate>())
    val prepareRequests = CopyOnWriteArrayList<PrepareRequest>()
    val publications = CopyOnWriteArrayList<Publication>()

    private val renderedCoverage = Collections.synchronizedSet(mutableSetOf<ViewportRenderRequirement>())
    private val renders = AtomicInteger()
    private val pageFramePublishCount = AtomicInteger()
    private val pageFrameReleases = AtomicInteger()
    private val preparedReleases = AtomicInteger()
    private val coverage = AtomicReference<IntRect?>(null)
    private val failRollingWindowAsOverBudget = AtomicBoolean(false)
    private val failRollingWindowsAsOverBudget = AtomicBoolean(false)

    /** serialises frame installation against anything observing what is installed */
    private val installLock = ReentrantLock()

    private val pageFrameGate = AtomicReference(CompletableDeferred<Unit>().apply { complete(Unit) })
    private val pageFramePainter = FixturePainter("page-frame")

    @Volatile
    var pageFrameAvailable: Boolean = pageFrameAvailable

    val nativeRenders: Int get() = renders.get()
    val pageFramePublications: Int get() = pageFramePublishCount.get()
    val pageFrameLeaseReleases: Int get() = pageFrameReleases.get()
    val preparedLeaseReleases: Int get() = preparedReleases.get()

    /** the fitted display region the currently installed frame can draw */
    val installedCoverage: IntRect? get() = coverage.get()

    /** notified at the end of each installation, while installation is still held */
    @Volatile
    var frameInstalled: (() -> Unit)? = null

    /**
     * Reads the installed frame with installation excluded for the duration, so a reader can never
     * see the coverage of one frame beside the painter of another, nor straddle an installation.
     */
    fun <T> withInstalledFrameHeld(read: (coverage: IntRect?, painter: Painter?) -> T): T =
        installLock.withLock { read(coverage.get(), painter.value) }

    fun blockPageFramePublication() {
        pageFrameGate.set(CompletableDeferred())
    }

    fun releasePageFramePublication() {
        pageFrameGate.get().complete(Unit)
    }

    fun blockPreparation() {
        preparationGate.set(CompletableDeferred())
    }

    fun failNextRollingWindowAsOverBudget() {
        failRollingWindowAsOverBudget.set(true)
    }

    fun failRollingWindowsAsOverBudget() {
        failRollingWindowsAsOverBudget.set(true)
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
        isStillValid: () -> Boolean,
    ): PreparedUpdateLease {
        val request = PrepareRequest(maxDisplaySize, requirements)
        prepareRequests += request
        preparationStarted.complete(request)
        val sharpRequirementCount = requirements.count { !it.overscan }
        if (
            failRollingWindowAsOverBudget.compareAndSet(true, false) ||
            failRollingWindowsAsOverBudget.get() && sharpRequirementCount >= 3
        ) {
            throw TilePixelBudgetExceededException("controlled rolling window exceeds pixel budget")
        }
        if (requirements.any { it !in renderedCoverage }) {
            preparationGate.get().await()
        }

        if (!isStillValid()) throw kotlinx.coroutines.CancellationException("image update is obsolete")
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
        return PreparedUpdateLease { preparedReleases.incrementAndGet() }
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease = prepareUpdate(maxDisplaySize, requirements) { true }

    private fun install(publication: Publication, region: IntRect, frame: Painter) {
        installLock.withLock {
            coverage.set(region)
            painter.value = frame
            publications += publication
            frameInstalled?.invoke()
        }
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
