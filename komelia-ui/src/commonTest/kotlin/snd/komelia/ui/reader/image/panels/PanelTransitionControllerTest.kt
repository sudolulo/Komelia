package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import snd.komelia.image.KomeliaImage
import snd.komelia.image.PreparedUpdateLease
import snd.komelia.image.ReaderImage
import snd.komelia.image.ViewportRenderRequirement
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PanelTransitionControllerTest {
    @Test
    fun `cancel scroll cancels the returned animation job`() = runTest {
        val state = ScreenScaleState().apply {
            composeScope = this@runTest
            setAreaSize(IntSize(500, 500))
            setTargetSize(IntSize(1000, 1000).toSize())
            setZoom(2f)
        }

        val animation = state.scrollTo(Offset(100f, 100f))

        assertTrue(animation.isActive)
        state.cancelScroll()
        assertTrue(animation.isCancelled)
    }

    @Test
    fun `panel index zoom and offset remain unchanged until the page frame is published`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.startNextPanel()
        fixture.awaitPreparationStarted()

        assertEquals(0, fixture.currentPanelIndex)
        assertEquals(fixture.startTransformation, fixture.transformation)

        fixture.completePreparation()
        fixture.runCurrent()

        assertEquals(1, fixture.currentPanelIndex)
        assertTrue(fixture.animationStarted)
    }

    @Test
    fun `repeated panel input is ignored while preparation is pending`() = runTest {
        val fixture = panelTransitionFixture()
        assertTrue(fixture.startNextPanel())
        fixture.awaitPreparationStarted()

        assertFalse(fixture.startNextPanel())
        assertEquals(1, fixture.preparationStarts)
    }

    @Test
    fun `page replacement during preparation never commits stale movement`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.startNextPanel()
        fixture.awaitPreparationStarted()
        fixture.replacePage()
        fixture.completePreparation()
        fixture.runCurrent()

        assertEquals(0, fixture.commits)
        assertEquals(fixture.startTransformation, fixture.transformation)
        assertEquals(1, fixture.preparedUpdateReleases)
    }

    @Test
    fun `area or target size change during preparation never commits stale movement`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.startNextPanel()
        fixture.awaitPreparationStarted()
        fixture.resizeScreen()
        fixture.completePreparation()
        fixture.runCurrent()

        assertEquals(0, fixture.commits)
    }

    @Test
    fun `render failure leaves current panel and transformation unchanged`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.failPreparation(IllegalStateException("tile render failed"))
        fixture.startNextPanel()
        fixture.runCurrent()

        assertEquals(0, fixture.currentPanelIndex)
        assertEquals(fixture.startTransformation, fixture.transformation)
        assertEquals("tile render failed", fixture.reportedError?.message)
    }

    @Test
    fun `preparation cancellation is not reported as a rendering failure`() = runTest {
        var reportedError: Throwable? = null
        val controller = PanelTransitionController(backgroundScope) { reportedError = it }

        controller.launch(
            prepare = { throw CancellationException("preparation cancelled") },
            isStillValid = { true },
            commit = {},
        )
        runCurrent()

        assertNull(reportedError)
        assertEquals(PanelTransitionPhase.Idle, controller.phase.value)
    }

    @Test
    fun `commit cancellation is not reported as a rendering failure`() = runTest {
        var reportedError: Throwable? = null
        val controller = PanelTransitionController(backgroundScope) { reportedError = it }

        controller.launch(
            prepare = { PreparedUpdateLease {} },
            isStillValid = { true },
            commit = { throw CancellationException("commit cancelled") },
        )
        runCurrent()

        assertNull(reportedError)
        assertEquals(PanelTransitionPhase.Idle, controller.phase.value)
    }

    @Test
    fun `normal viewport updates are suppressed until animation completes`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.startNextPanel()
        fixture.completePreparation()
        fixture.awaitAnimationStarted()
        fixture.emitAnimationFrames(10)

        assertEquals(0, fixture.normalViewportUpdates)
        assertEquals(0, fixture.preparedUpdateReleases)
        fixture.completeAnimation()
        fixture.runCurrent()
        assertEquals(1, fixture.normalViewportUpdates)
        assertEquals(1, fixture.preparedUpdateReleases)
    }

    @Test
    fun `buffered idle viewport update is suppressed after animation starts`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.bufferViewportChange()
        fixture.startNextPanel()
        fixture.completePreparation()
        fixture.awaitAnimationStarted()

        fixture.consumeBufferedViewportChange()

        assertEquals(0, fixture.normalViewportUpdates)
    }

    @Test
    fun `animation cancellation resumes one destination viewport update`() = runTest {
        val fixture = panelTransitionFixture()
        fixture.startNextPanel()
        fixture.completePreparation()
        fixture.awaitAnimationStarted()
        fixture.cancelTransition()
        fixture.runCurrent()

        assertEquals(PanelTransitionPhase.Idle, fixture.phase)
        assertEquals(1, fixture.normalViewportUpdates)
        assertEquals(1, fixture.preparedUpdateReleases)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
private class PanelTransitionFixture(
    private val scope: TestScope,
) {
    private val image = TestReaderImage()
    private val controller = PanelTransitionController(scope.backgroundScope) { reportedError = it }
    private val preparationStarted = CompletableDeferred<Unit>()
    private val preparation = CompletableDeferred<Unit>()
    private val animationStartedSignal = CompletableDeferred<Unit>()
    private val animation = CompletableDeferred<Unit>()
    private var preparationFailure: Throwable? = null
    private var bufferedViewportChange: (suspend () -> Unit)? = null
    private var pageIdentity = Any()
    private var areaSize = IntSize(1080, 2208)
    private var targetSize = IntSize(1080, 1669)

    val startTransformation = Transformation(Offset.Zero, 1f)
    var transformation = startTransformation
        private set
    var currentPanelIndex = 0
        private set
    var preparationStarts = 0
        private set
    var commits = 0
        private set
    var reportedError: Throwable? = null
    var animationStarted = false
        private set
    val normalViewportUpdates: Int
        get() = image.viewportUpdates
    val preparedUpdateReleases: Int
        get() = image.preparedUpdateReleases
    val phase: PanelTransitionPhase
        get() = controller.phase.value

    fun startNextPanel(): Boolean {
        val sourcePage = pageIdentity
        val sourcePanelIndex = currentPanelIndex
        val sourceAreaSize = areaSize
        val sourceTargetSize = targetSize
        val sourceTransformation = transformation

        return controller.launch(
            prepare = {
                preparationStarts++
                preparationStarted.complete(Unit)
                preparationFailure?.let { throw it }
                val preparedUpdate = checkNotNull(image.publishPageFrame())
                preparation.await()
                preparedUpdate
            },
            isStillValid = {
                pageIdentity === sourcePage &&
                    currentPanelIndex == sourcePanelIndex &&
                    areaSize == sourceAreaSize &&
                    targetSize == sourceTargetSize &&
                    transformation == sourceTransformation
            },
            commit = {
                commits++
                currentPanelIndex = 1
                transformation = Transformation(Offset(100f, 200f), 2f)
                onTransformationOrAreaChanged()
                animationStarted = true
                animationStartedSignal.complete(Unit)
                animation.await()
            },
            afterRelease = { requestNormalViewportUpdate() },
        )
    }

    suspend fun awaitPreparationStarted() = preparationStarted.await()

    fun completePreparation() {
        image.releasePreparation()
        preparation.complete(Unit)
    }

    suspend fun awaitAnimationStarted() = animationStartedSignal.await()

    suspend fun emitAnimationFrames(count: Int) {
        repeat(count) { frame ->
            transformation = transformation.copy(offset = Offset(frame.toFloat(), frame.toFloat()))
            onTransformationOrAreaChanged()
        }
    }

    fun bufferViewportChange() {
        val change = controller.captureViewportChange()
        bufferedViewportChange = {
            controller.onViewportChanged(change) {
                requestNormalViewportUpdate()
            }
        }
    }

    suspend fun consumeBufferedViewportChange() {
        checkNotNull(bufferedViewportChange).invoke()
    }

    fun completeAnimation() {
        animation.complete(Unit)
    }

    fun cancelTransition() = controller.cancel()

    fun replacePage() {
        pageIdentity = Any()
    }

    fun resizeScreen() {
        areaSize = IntSize(1200, 2208)
        targetSize = IntSize(1200, 1854)
    }

    fun failPreparation(error: Throwable) {
        preparationFailure = error
    }

    fun runCurrent() = scope.runCurrent()

    private suspend fun onTransformationOrAreaChanged() {
        controller.onViewportChanged {
            requestNormalViewportUpdate()
        }
    }

    private fun requestNormalViewportUpdate() {
        image.requestUpdate(
            maxDisplaySize = areaSize,
            zoomFactor = transformation.scale,
            visibleDisplaySize = IntRect(0, 0, targetSize.width, targetSize.height),
        )
    }
}

private fun TestScope.panelTransitionFixture() = PanelTransitionFixture(this)

private class TestReaderImage : ReaderImage {
    private val preparation = CompletableDeferred<Unit>()
    private val pageFrame = object : Painter() {
        override val intrinsicSize = Size.Zero
        override fun DrawScope.onDraw() = Unit
    }

    var viewportUpdates = 0
        private set
    var preparedUpdateReleases = 0
        private set
    private var preparedUpdateHeld = false

    override val pageId = ReaderImage.PageId("book", 1)
    override val originalSize = MutableStateFlow<IntSize?>(null)
    override val displaySize = MutableStateFlow<IntSize?>(null)
    override val currentSize = MutableStateFlow<IntSize?>(null)
    override val painter = MutableStateFlow<Painter?>(null)
    override val error = MutableStateFlow<Throwable?>(null)

    override fun requestUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ) {
        check(!preparedUpdateHeld) { "normal viewport published before prepared update release" }
        viewportUpdates++
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease {
        preparation.await()
        return publicationLock()
    }

    override suspend fun publishPageFrame(): PreparedUpdateLease {
        preparation.await()
        painter.value = pageFrame
        return publicationLock()
    }

    private fun publicationLock(): PreparedUpdateLease {
        preparedUpdateHeld = true
        return PreparedUpdateLease {
            if (preparedUpdateHeld) {
                preparedUpdateHeld = false
                preparedUpdateReleases++
            }
        }
    }

    fun releasePreparation() {
        preparation.complete(Unit)
    }

    override suspend fun getOriginalImageSize(): Result<IntSize> =
        Result.failure(UnsupportedOperationException())

    override suspend fun getOriginalImage(): Result<KomeliaImage> =
        Result.failure(UnsupportedOperationException())

    override fun close() = Unit
}
