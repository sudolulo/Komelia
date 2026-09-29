package snd.komelia.ui.reader.image.panels

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import snd.komelia.AppNotifications
import snd.komelia.image.BookImageLoader
import snd.komelia.image.ImageRect
import snd.komelia.image.KomeliaPanelDetector
import snd.komelia.image.ReaderImage
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.ReaderImageResult
import snd.komelia.onnxruntime.OnnxRuntimeException
import snd.komelia.settings.ImageReaderSettingsRepository
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.settings.model.PagedReadingDirection.LEFT_TO_RIGHT
import snd.komelia.settings.model.PagedReadingDirection.RIGHT_TO_LEFT
import snd.komelia.settings.model.PanelTransitionMode
import snd.komelia.ui.reader.image.BookState
import snd.komelia.ui.reader.image.CLASSIC_TRANSITION_DURATION_MS
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.ReaderState
import snd.komelia.ui.reader.image.SNAPPY_TRANSITION_DURATION_MS
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.transformationForZoom
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage.BookEnd
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage.BookStart
import snd.komga.client.common.KomgaReadingDirection
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger { }

/**
 * How long the area-size collector waits after handling one resize before it looks at the next.
 * A drag emits an area size per layout pass; each accepted one costs a full preparation on the
 * renderer's single processing lane, so they are throttled to this window.
 */
private const val resizeThrottleMillis = 100L

internal fun PanelTransitionMode.durationMillis(): Int = when (this) {
    PanelTransitionMode.SNAPPY -> SNAPPY_TRANSITION_DURATION_MS
    PanelTransitionMode.CLASSIC -> CLASSIC_TRANSITION_DURATION_MS
}

/** How one panel transition ended. Exactly one outcome is reported per accepted transition. */
enum class PanelTransitionOutcome {
    /** the movement animation ran to completion */
    ANIMATED,

    /** the reader moved on before the transition could be used */
    SUPERSEDED,

    /** the transition was cancelled before it finished */
    CANCELLED,

    /** the transition failed; the throwable itself is reported through the error path */
    FAILED,
}

/** Where the frame a transition moved on came from. */
enum class PanelTransitionFrameSource {
    /** the sharp rolling panel window was prepared or reacquired for movement */
    ROLLING_WINDOW,

    /** the retained fit-zoom page frame was republished; nothing was rendered to move */
    RETAINED_PAGE_FRAME,

    /** no page frame was available, so the whole page was rendered at fit zoom before movement */
    RENDERED_FIT_ZOOM_PAGE,
}

/**
 * One transition as this state observed it: the generation, mode, duration and movement timings
 * it owns, plus where the frame it moved on came from.
 *
 * Every time is milliseconds elapsed since the input was accepted. A phase a transition never
 * reached reports the elapsed time at which the transition ended instead.
 *
 * Carries no book title, source URL, server URL, user identifier or credential.
 */
data class PanelTransitionEvent(
    val generation: Long,
    val mode: PanelTransitionMode,
    /** The full movement duration configured by [mode], including terminal pre-movement outcomes. */
    val durationMillis: Int,
    val outcome: PanelTransitionOutcome,
    /**
     * Where the frame this transition moved on came from, or null when the transition ended
     * before it got as far as choosing one.
     */
    val frameSource: PanelTransitionFrameSource?,
    val readinessMillis: Long,
    val movementStartMillis: Long,
    val movementEndMillis: Long,
) {
    /**
     * Whether the retained page frame was republished, so the transition moved immediately. It is
     * derived from [frameSource] and cannot contradict it: a transition that had to render its
     * swept path first reports false here, and reports the wait in [readinessMillis].
     */
    val pageFrameAvailable: Boolean
        get() = frameSource == PanelTransitionFrameSource.RETAINED_PAGE_FRAME

    fun toLogLine(): String = "panel transition $generation $outcome mode=$mode " +
        "duration=${durationMillis}ms frame=$frameSource " +
        "readiness=${readinessMillis}ms " +
        "movement=${movementStartMillis}ms..${movementEndMillis}ms"
}

/** Where [PanelTransitionEvent]s go. The default sink logs them. */
fun interface PanelTransitionDiagnostics {
    fun onTransition(event: PanelTransitionEvent)

    companion object {
        val Logging = PanelTransitionDiagnostics { event -> logger.info { event.toLogLine() } }
    }
}

internal suspend fun awaitFirstPainter(image: ReaderImage?) {
    image ?: return
    coroutineScope {
        select {
            async { image.painter.filterNotNull().first() }.onAwait { }
            async { image.error.filterNotNull().first() }.onAwait { }
        }
        coroutineContext.cancelChildren()
    }
}

class PanelsReaderState(
    private val cleanupScope: CoroutineScope,
    private val settingsRepository: ImageReaderSettingsRepository,
    private val appNotifications: AppNotifications,
    private val readerState: ReaderState,
    private val imageLoader: BookImageLoader,
    private val pageChangeFlow: MutableSharedFlow<Unit>,
    private val onnxRuntimeRfDetr: KomeliaPanelDetector,
    val screenScaleState: ScreenScaleState,
    private val transitionDiagnostics: PanelTransitionDiagnostics = PanelTransitionDiagnostics.Logging,
) {
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val panelTransitionMode = settingsRepository.getPanelTransitionMode()
        .stateIn(stateScope, SharingStarted.Eagerly, PanelTransitionMode.SNAPPY)
    private val panelTransitionController = PanelTransitionController(stateScope) { error ->
        logger.catching(error)
    }
    private val pageLoadCoordinator = PageLoadCoordinator<PageId, PanelsPage>(
        cleanupScope = cleanupScope,
        closeValue = { it.imageResult?.image?.close() },
    )
    private val pendingPageIndex = MutableStateFlow<Int?>(null)

    /** one monotonically increasing generation per accepted transition, for correlation */
    private val transitionGeneration = MutableStateFlow(0L)

    // The final animation value can reach the conflated observer after the controller returns to
    // Idle. Suppress that exact duplicate so it cannot replace the rolling window with an ordinary
    // overscanned viewport update.
    private val suppressedViewportUpdate = MutableStateFlow<ViewportUpdateSnapshot?>(null)
    private val resizeGeneration = MutableStateFlow(0L)
    private var resizeJob: Job? = null
    private var rollingWindowJob: Job? = null
    private var rollingWindowKey: RollingWindowKey? = null

    val pageMetadata: MutableStateFlow<List<PageMetadata>> = MutableStateFlow(emptyList())

    val currentPageIndex = MutableStateFlow(PageIndex(0, 0, false))
    val currentPage: MutableStateFlow<PanelsPage?> = MutableStateFlow(null)
    val transitionPage: MutableStateFlow<TransitionPage?> = MutableStateFlow(null)
    val readingDirection = MutableStateFlow(LEFT_TO_RIGHT)

    suspend fun initialize() {
        readingDirection.value = when (readerState.series.value?.metadata?.readingDirection) {
            KomgaReadingDirection.LEFT_TO_RIGHT -> LEFT_TO_RIGHT
            KomgaReadingDirection.RIGHT_TO_LEFT -> RIGHT_TO_LEFT
            else -> settingsRepository.getPagedReaderReadingDirection().first()
        }

        screenScaleState.setScrollState(null)
        screenScaleState.setScrollOrientation(Orientation.Vertical, false)

        screenScaleState.transformation
            .drop(1)
            .map { panelTransitionController.captureViewportChange() }
            .conflate()
            .onEach { change ->
                panelTransitionController.onViewportChanged(change) {
                    currentPage.value?.let { page ->
                        updateImageState(page, screenScaleState)
                    }
                }
                delay(100)
            }
            .launchIn(stateScope)

        screenScaleState.areaSize
            .drop(1)
            .conflate()
            .onEach { areaSize ->
                val generation = resizeGeneration.updateAndGet { it + 1 }
                resizeJob?.cancel()
                cancelPanelTransition()

                val page = currentPage.value ?: return@onEach
                val pageIndex = currentPageIndex.value
                resizeJob = stateScope.launch {
                    prepareMaterialResize(page, pageIndex, areaSize, generation)
                }
                // Cancelling resizeJob does not cancel work the arbiter already accepted, and the
                // arbiter's pending queue neither coalesces nor drops superseded demands. So the
                // bound has to be here: conflate plus this delay throttles a resize drag to one
                // preparation per window instead of one per intermediate size. conflate keeps the
                // latest value, so the size the drag ends on is still prepared.
                delay(resizeThrottleMillis)
            }
            .launchIn(stateScope)

        readingDirection.drop(1).onEach { readingDirection ->
            cancelPanelTransition()
            val page = currentPage.value
            val panelData = page?.panelData
            if (panelData != null) {
                val sortedPanels = sortPanels(
                    panels = panelData.panels,
                    imageSize = panelData.originalImageSize,
                    readingDirection = readingDirection
                )
                currentPage.value = page.copy(panelData = panelData.copy(panels = sortedPanels))
                currentPageIndex.update { it.copy(panel = 0, isLastPanelZoomOutActive = false) }

                if (sortedPanels.isNotEmpty()) {
                    scrollToPanel(
                        imageSize = page.panelData.originalImageSize,
                        screenSize = screenScaleState.areaSize.value,
                        targetSize = screenScaleState.targetSize.value.toIntSize(),
                        panel = sortedPanels.first()
                    )
                }

            }

        }.launchIn(stateScope)

        readerState.booksState
            .filterNotNull()
            .onEach { newBook -> onNewBookLoaded(newBook) }
            .launchIn(stateScope)
    }

    fun stop() {
        resizeGeneration.update { it + 1 }
        cancelPanelTransition()
        rollingWindowJob?.cancel()
        rollingWindowJob = null
        rollingWindowKey = null
        stateScope.coroutineContext.cancelChildren()
        pageLoadCoordinator.stop { currentPage.value = null }
        pendingPageIndex.value = null
        screenScaleState.enableOverscrollArea(false)
    }

    private suspend fun updateImageState(
        page: PanelsPage,
        screenScaleState: ScreenScaleState,
    ) {
        if (consumeSuppressedViewportUpdate(page, screenScaleState)) return

        val maxPageSize = screenScaleState.areaSize.value
        val transformation = screenScaleState.transformation.value
        val zoomFactor = transformation.scale
        val stretchToFit = readerState.imageStretchToFit.value


        if (page.imageResult is ReaderImageResult.Success) {
            val image = page.imageResult.image
            val imageDisplaySize = image.calculateSizeForArea(maxPageSize, stretchToFit) ?: return
            screenScaleState.setTargetSize(imageDisplaySize.toSize())

            val visibleArea = visibleDisplayRegion(
                displaySize = imageDisplaySize,
                areaSize = maxPageSize,
                transformation = transformation,
            )

            image.requestUpdate(
                visibleDisplaySize = visibleArea,
                zoomFactor = zoomFactor,
                maxDisplaySize = maxPageSize
            )
        }
    }

    /**
     * Re-enters the panel the reader is on at the geometry of a new area size: the page-load
     * path's fit geometry, the ordinary viewport update that goes with it, and then the same
     * animated transition [nextPanel] and [previousPanel] use, which prepares its own
     * destination. A resize that a newer one has already superseded stops without moving.
     */
    private suspend fun prepareMaterialResize(
        page: PanelsPage,
        pageIndex: PageIndex,
        areaSize: IntSize,
        generation: Long,
    ) {
        val panel = page.panelData?.panels?.getOrNull(pageIndex.panel) ?: return
        val isStillValid = {
            resizeGeneration.value == generation &&
                currentPage.value === page &&
                currentPageIndex.value == pageIndex &&
                screenScaleState.areaSize.value == areaSize
        }

        val fitState = getScaleFor(page, areaSize)
        if (!isStillValid()) return

        screenScaleState.apply(fitState)
        // fire and forget, exactly as the stock resize path did: the renderer conflates these,
        // so a drag costs at most one queued viewport render rather than one per emitted size
        updateImageState(page, screenScaleState)

        if (isStillValid()) {
            prepareRollingWindow()
            launchPanelTransition(pageIndex.panel, panel)
        }
    }

    private fun onNewBookLoaded(bookState: BookState) {
        cancelPanelTransition()
        val newPages = bookState.currentBookPages
        val newPageIndex = readerState.readProgressPage.value - 1

        pageMetadata.value = bookState.currentBookPages
        if (currentPage.value?.metadata?.bookId != newPages[newPageIndex].bookId) {
            pageLoadCoordinator.unpublishCurrent { currentPage.value = null }
            currentPageIndex.value = PageIndex(newPageIndex, 0, false)
        }

        launchPageLoad(newPageIndex)
    }

    fun onReadingDirectionChange(readingDirection: PagedReadingDirection) {
        cancelPanelTransition()
        this.readingDirection.value = readingDirection
        stateScope.launch { settingsRepository.putPagedReaderReadingDirection(readingDirection) }
    }


    fun nextPanel() {
        if (pendingPageIndex.value != null || panelTransitionController.isActive) return
        val pageIndex = currentPageIndex.value
        val currentPage = currentPage.value
        if (currentPage == null || currentPage.panelData == null) {
            nextPage()
            return
        }
        val panelData = currentPage.panelData
        val panels = panelData.panels
        val panelIndex = pageIndex.panel

        if (panels.size <= panelIndex + 1) {
            if (panels.isEmpty() || panelData.panelCoversMajorityOfImage || pageIndex.isLastPanelZoomOutActive) {
                nextPage()
            } else {
                scrollToFit()
                currentPageIndex.update { it.copy(isLastPanelZoomOutActive = true) }
            }
            return
        }
        launchPanelTransition(panelIndex + 1, panels[panelIndex + 1])
    }

    private fun nextPage() {
        cancelPanelTransition()
        val currentPageIndex = currentPageIndex.value.page
        val currentTransitionPage = transitionPage.value
        when {
            currentPageIndex < pageMetadata.value.size - 1 -> {
                if (currentTransitionPage != null) this.transitionPage.value = null
                else onPageChange(currentPageIndex + 1)
            }

            currentTransitionPage == null -> {
                val bookState = readerState.booksState.value ?: return
                this.transitionPage.value = BookEnd(
                    currentBook = bookState.currentBook,
                    nextBook = bookState.nextBook
                )
            }

            currentTransitionPage is BookEnd && currentTransitionPage.nextBook != null -> {
                stateScope.launch {
                    pageLoadCoordinator.unpublishCurrent { currentPage.value = null }.join()
                    transitionPage.value = null
                    readerState.loadNextBook()
                }
            }
        }
    }

    fun previousPanel() {
        if (pendingPageIndex.value != null || panelTransitionController.isActive) return
        val pageIndex = currentPageIndex.value
        val currentPage = currentPage.value
        if (currentPage == null || currentPage.panelData == null) {
            previousPage()
            return
        }
        val panels = currentPage.panelData.panels
        val panelIndex = pageIndex.panel

        if (panelIndex - 1 < 0) {
            previousPage()
            return
        }
        launchPanelTransition(panelIndex - 1, panels[panelIndex - 1])
    }

    private fun previousPage() {
        cancelPanelTransition()
        val currentPgeIndex = currentPageIndex.value.page
        val currentTransitionPage = transitionPage.value
        when {
            currentPgeIndex != 0 -> {
                if (currentTransitionPage != null) this.transitionPage.value = null
                else onPageChange(currentPgeIndex - 1)
            }

            currentTransitionPage == null -> {
                val bookState = readerState.booksState.value ?: return
                this.transitionPage.value = BookStart(
                    currentBook = bookState.currentBook,
                    previousBook = bookState.previousBook
                )
            }

            currentTransitionPage is BookStart && currentTransitionPage.previousBook != null -> {
                stateScope.launch {
                    pageLoadCoordinator.unpublishCurrent { currentPage.value = null }.join()
                    transitionPage.value = null
                    readerState.loadPreviousBook()
                }
            }
        }
    }

    fun onPageChange(page: Int) {
        if (pendingPageIndex.value == page) return
        if (pendingPageIndex.value == null && currentPageIndex.value.page == page) return
        cancelPanelTransition()
        pageChangeFlow.tryEmit(Unit)
        launchPageLoad(page)
    }

    private fun launchPageLoad(pageIndex: Int) {
        if (pageIndex != currentPageIndex.value.page) {
            val pageNumber = pageIndex + 1
            stateScope.launch { readerState.onProgressChange(pageNumber) }
        }

        val pageMeta = pageMetadata.value[pageIndex]
        pendingPageIndex.value = pageIndex
        pageLoadCoordinator.navigate(
            key = pageMeta.toPageId(),
            load = { loadPage(pageMeta) },
            prepare = { preparePage(it) },
            publish = { readyPage ->
                if (pendingPageIndex.value != pageIndex) return@navigate false
                currentPageIndex.value = PageIndex(pageIndex, 0, false)
                transitionPage.value = null
                logger.info { "current page value ${readyPage.page}" }
                currentPage.value = readyPage.page
                screenScaleState.enableOverscrollArea(true)
                screenScaleState.apply(readyPage.scale)
                pendingPageIndex.compareAndSet(pageIndex, null)
                true
            },
            afterReady = {
                enterFirstPanel()
                preloadImagesBetween(pageIndex)
            },
        )
    }

    private suspend fun preparePage(page: PanelsPage): ReadyPanelsPage {
        val sortedPanelsPage = if (page.panelData != null) {
            val sortedPanels = sortPanels(
                page.panelData.panels,
                page.panelData.originalImageSize,
                readingDirection.value
            )
            page.copy(panelData = page.panelData.copy(panels = sortedPanels))
        } else page

        val containerSize = screenScaleState.areaSize.value
        val scale = getScaleFor(sortedPanelsPage, containerSize)
        updateImageState(sortedPanelsPage, scale)
        awaitFirstPainter(sortedPanelsPage.imageResult?.image)
        return ReadyPanelsPage(sortedPanelsPage, scale)
    }

    private suspend fun preloadImagesBetween(pageIndex: Int) = coroutineScope {
        val previousPage = (pageIndex - 1).coerceAtLeast(0)
        val nextPage = (pageIndex + 1).coerceAtMost(pageMetadata.value.size - 1)
        val loadRange = (previousPage..nextPage).filter { it != pageIndex }

        for (index in loadRange) {
            val metadata = pageMetadata.value[index]
            launch {
                val imageJob = pageLoadCoordinator.prefetch(metadata.toPageId()) { loadPage(metadata) }
                val image = imageJob.await()
                preparePage(image)
            }
        }
    }

    private suspend fun loadPage(meta: PageMetadata): PanelsPage {
        val imageResult = imageLoader.loadReaderImage(meta.bookId, meta.pageNumber)
        val image = imageResult.image ?: return PanelsPage(
            metadata = meta,
            imageResult = imageResult,
            panelData = null
        )

        try {
            val originalImage = image.getOriginalImage().getOrNull()
                ?: return PanelsPage(
                    metadata = meta,
                    imageResult = imageResult,
                    panelData = null
                )

            val imageSize = IntSize(originalImage.width, originalImage.height)
            val (panels, duration) = measureTimedValue {
                try {
                    logger.info { "rf detr before run" }
                    onnxRuntimeRfDetr.detect(originalImage).map { it.boundingBox }
                } catch (e: OnnxRuntimeException) {
                    image.close()
                    return PanelsPage(
                        metadata = meta,
                        imageResult = ReaderImageResult.Error(e),
                        panelData = null
                    )
                }
            }
            logger.info { "page ${meta.pageNumber} panel detection completed in $duration" }


            val panelsArea = areaOfRects(panels.map { it.toRect() })
            val imageArea = originalImage.width * originalImage.height
            val untrimmedRatio = panelsArea / imageArea

            val panelRatio = if (untrimmedRatio < .8f) {
                val trim = originalImage.findTrim()
                val imageArea = trim.width * trim.height
                val ratio = panelsArea / imageArea
                logger.info { "trimmed panels area coverage ${ratio * 100}%" }
                ratio
            } else {
                logger.info { "untrimmed panels area coverage ${untrimmedRatio * 100}%" }
                untrimmedRatio
            }

            val panelData = PanelData(
                panels = panels,
                originalImageSize = imageSize,
                panelCoversMajorityOfImage = panelRatio > .8f
            )

            return PanelsPage(
                metadata = meta,
                imageResult = imageResult,
                panelData = panelData
            )
        } catch (e: Throwable) {
            image.close()
            currentCoroutineContext().ensureActive()
            return PanelsPage(
                metadata = meta,
                imageResult = ReaderImageResult.Error(e),
                panelData = null,
            )
        }
    }

    internal suspend fun getScaleFor(
        page: PanelsPage,
        containerSize: IntSize
    ): ScreenScaleState {
        val defaultScale = ScreenScaleState()
        defaultScale.setAreaSize(containerSize)
        defaultScale.setZoom(0f)
        val image = page.imageResult?.image ?: return defaultScale

        val scaleState = ScreenScaleState()
        val fitToScreenSize = image.calculateSizeForArea(containerSize, true) ?: return defaultScale
        scaleState.setAreaSize(containerSize)
        scaleState.setTargetSize(fitToScreenSize.toSize())
        scaleState.enableOverscrollArea(true)

        // First paint stays at fit zoom: that keeps the render area under the tiling
        // threshold, so doFullResize runs and retains the page frame every panel
        // transition on this page then publishes without rendering. The move into
        // panel 0 happens afterwards, as an ordinary animated transition.
        scaleState.setZoom(0f)

        return scaleState
    }

    private fun scrollToFit() {
//        val areaSize = screenScaleState.areaSize.value
//        val startX = 0 - areaSize.width.toFloat()
//        val startY = 0 - areaSize.height.toFloat()
        screenScaleState.setZoom(0f)
        screenScaleState.scrollTo(Offset(0f, 0f))

    }

    /**
     * Moves from the fit-zoom first paint into the page's first panel. Returns false when the
     * page has no panels, or when a transition is already running, in which case the reader
     * simply stays at fit zoom showing the whole page.
     */
    internal fun enterFirstPanel(): Boolean {
        val panels = currentPage.value?.panelData?.panels
        if (panels.isNullOrEmpty()) return false
        prepareRollingWindow()
        return launchPanelTransition(0, panels.first())
    }

    /**
     * Publishes the fit page plus the exact previous/current/next panel window in the background.
     * The lease is only a publication lock; retained tiles keep the published frame alive after it
     * closes. A newer window cancels an obsolete preparation before taking its place.
     */
    internal fun prepareRollingWindow(): Boolean {
        rollingWindowJob?.cancel()
        rollingWindowJob = null
        rollingWindowKey = null

        val page = currentPage.value ?: return false
        val panelData = page.panelData ?: return false
        val image = (page.imageResult as? ReaderImageResult.Success)?.image ?: return false
        val panelIndex = currentPageIndex.value.panel
        val areaSize = screenScaleState.areaSize.value
        val targetSize = screenScaleState.targetSize.value.toIntSize()
        val fitScale = screenScaleState.transformationForZoom(0f).scale
        val fitPage = fitPageRequirement(targetSize, fitScale) ?: return false
        val panelRequirements = rollingPanelRequirements(
            panels = panelData.panels,
            currentPanelIndex = panelIndex,
            areaSize = areaSize,
            targetSize = targetSize,
            transformationForZoom = screenScaleState::transformationForZoom,
            imageSize = panelData.originalImageSize,
        )
        if (panelRequirements.isEmpty()) return false

        val key = RollingWindowKey(image, panelIndex, areaSize, targetSize)
        rollingWindowKey = key
        rollingWindowJob = stateScope.launch {
            try {
                image.prepareUpdate(
                    maxDisplaySize = areaSize,
                    requirements = listOf(fitPage) + panelRequirements,
                ).close()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                logger.catching(error)
            }
        }
        return true
    }

    private fun launchPanelTransition(targetPanelIndex: Int, panel: ImageRect): Boolean {
        if (pendingPageIndex.value != null || panelTransitionController.isActive) return false
        // the mode is snapshotted here, so a change during movement only affects the next one
        val transitionMode = panelTransitionMode.value
        val transitionDurationMillis = transitionMode.durationMillis()

        val sourcePage = currentPage.value ?: return false
        val panelData = sourcePage.panelData ?: return false
        val image = (sourcePage.imageResult as? ReaderImageResult.Success)?.image ?: return false
        val sourceIndex = currentPageIndex.value
        val areaSize = screenScaleState.areaSize.value
        val targetSize = screenScaleState.targetSize.value.toIntSize()
        val sourceTransformation = screenScaleState.transformation.value
        val target = panelTarget(
            panelData = panelData,
            areaSize = areaSize,
            targetSize = targetSize,
            sourceTransformation = sourceTransformation,
            panel = panel,
        ) ?: return false

        val fitZoomScale = screenScaleState.transformationForZoom(0f).scale
        val fitZoomPage = fitPageRequirement(targetSize, fitZoomScale) ?: return false
        val rollingRequirements = rollingPanelRequirements(
            panels = panelData.panels,
            currentPanelIndex = sourceIndex.panel,
            areaSize = areaSize,
            targetSize = targetSize,
            transformationForZoom = screenScaleState::transformationForZoom,
            imageSize = panelData.originalImageSize,
        )
        if (rollingRequirements.isEmpty()) return false
        val transitionRequirements = listOf(fitZoomPage) + rollingRequirements
        val transitionWindowKey = RollingWindowKey(image, sourceIndex.panel, areaSize, targetSize)

        val transition = PreparedPanelTransition(
            sourcePage = sourcePage,
            sourceIndex = sourceIndex,
            areaSize = areaSize,
            targetSize = targetSize,
            targetPanelIndex = targetPanelIndex,
            targetZoom = target.targetZoom,
            targetOffset = target.targetOffset,
        )

        // every field of the event is owned by this layer, so the record is assembled here
        val generation = transitionGeneration.updateAndGet { it + 1 }
        val transitionStart = TimeSource.Monotonic.markNow()
        var frameSource: PanelTransitionFrameSource? = null
        var readinessMillis: Long? = null
        var movementStartMillis: Long? = null
        var movementEndMillis: Long? = null
        var eventEmitted = false

        fun emitTransitionEvent(outcome: PanelTransitionOutcome) {
            // every terminal path calls this; the first one wins, so one input is one event
            if (eventEmitted) return
            eventEmitted = true
            val elapsedMillis = transitionStart.elapsedNow().inWholeMilliseconds
            transitionDiagnostics.onTransition(
                PanelTransitionEvent(
                    generation = generation,
                    mode = transitionMode,
                    durationMillis = transitionDurationMillis,
                    outcome = outcome,
                    frameSource = frameSource,
                    readinessMillis = readinessMillis ?: elapsedMillis,
                    movementStartMillis = movementStartMillis ?: elapsedMillis,
                    movementEndMillis = movementEndMillis ?: elapsedMillis,
                )
            )
        }

        return panelTransitionController.launch(
            prepare = {
                try {
                    if (rollingWindowKey == transitionWindowKey) {
                        rollingWindowJob?.join()
                    }
                    val lease = image.prepareUpdate(
                        maxDisplaySize = transition.areaSize,
                        requirements = transitionRequirements,
                    )
                    frameSource = PanelTransitionFrameSource.ROLLING_WINDOW
                    readinessMillis = transitionStart.elapsedNow().inWholeMilliseconds
                    lease
                } catch (e: CancellationException) {
                    emitTransitionEvent(PanelTransitionOutcome.CANCELLED)
                    throw e
                } catch (e: Throwable) {
                    emitTransitionEvent(PanelTransitionOutcome.FAILED)
                    throw e
                }
            },
            isStillValid = {
                val valid = currentPage.value === transition.sourcePage &&
                    currentPageIndex.value == transition.sourceIndex &&
                    screenScaleState.areaSize.value == transition.areaSize &&
                    screenScaleState.targetSize.value.toIntSize() == transition.targetSize &&
                    screenScaleState.transformation.value == sourceTransformation
                // the controller runs nothing else once this reports false, so the terminal
                // event for a superseded transition has to be emitted here
                if (!valid) emitTransitionEvent(PanelTransitionOutcome.SUPERSEDED)
                valid
            },
            commit = {
                try {
                    currentCoroutineContext().ensureActive()
                    movementStartMillis = transitionStart.elapsedNow().inWholeMilliseconds
                    currentPageIndex.value = transition.sourceIndex.copy(
                        panel = transition.targetPanelIndex,
                        isLastPanelZoomOutActive = false,
                    )

                    screenScaleState.setZoom(transition.targetZoom)
                    val animation = screenScaleState.scrollTo(
                        offset = transition.targetOffset,
                        durationMillis = transitionDurationMillis,
                    )
                    try {
                        animation.join()
                    } finally {
                        if (animation.isActive) animation.cancel()
                    }
                    movementEndMillis = transitionStart.elapsedNow().inWholeMilliseconds
                    emitTransitionEvent(PanelTransitionOutcome.ANIMATED)
                } catch (e: CancellationException) {
                    emitTransitionEvent(PanelTransitionOutcome.CANCELLED)
                    throw e
                } catch (e: Throwable) {
                    emitTransitionEvent(PanelTransitionOutcome.FAILED)
                    throw e
                }
            },
            afterRelease = {
                currentPage.value?.let { page ->
                    suppressedViewportUpdate.value = viewportUpdateSnapshot(page, screenScaleState)
                    prepareRollingWindow()
                }
            },
        )
    }

    /**
     * Resolves where a transition to [panel] ends up, or null when the geometry is not usable yet.
     *
     * The destination viewport is computed here for one reason: to refuse a transition whose
     * arrival view is not renderable. A degenerate viewport - an area or fitted page of no extent,
     * a target scale that is not a positive finite number, or a destination region that rounds
     * away to nothing - has no view to arrive on, so the caller keeps its current frame instead.
     */
    private fun panelTarget(
        panelData: PanelData,
        areaSize: IntSize,
        targetSize: IntSize,
        sourceTransformation: ScreenScaleState.Transformation,
        panel: ImageRect,
    ): PanelTarget? {
        if (areaSize.width <= 0 || areaSize.height <= 0) return null
        if (targetSize.width <= 0 || targetSize.height <= 0) return null
        if (!sourceTransformation.scale.isFinite() || sourceTransformation.scale <= 0f) return null

        val (targetOffset, targetZoom) = getPanelOffsetAndZoom(
            imageSize = panelData.originalImageSize,
            areaSize = areaSize,
            targetSize = targetSize,
            panel = panel,
        )
        if (!targetZoom.isFinite() || targetZoom <= 0f) return null

        val targetScale = screenScaleState.transformationForZoom(targetZoom).scale
        if (!targetScale.isFinite() || targetScale <= 0f) return null

        // the ordinary viewport update after the movement renders exactly this region, so a region
        // of no extent means the movement would arrive on nothing that can be drawn
        val destinationRegion = visibleDisplayRegion(
            displaySize = targetSize,
            areaSize = areaSize,
            transformation = ScreenScaleState.Transformation(
                offset = targetOffset,
                scale = targetScale,
            ),
        )
        if (destinationRegion.width <= 0 || destinationRegion.height <= 0) {
            logger.debug {
                "panel transition destination $destinationRegion at scale $targetScale is not renderable"
            }
            return null
        }

        return PanelTarget(
            targetZoom = targetZoom,
            targetOffset = targetOffset,
        )
    }

    private fun consumeSuppressedViewportUpdate(
        page: PanelsPage,
        screenScaleState: ScreenScaleState,
    ): Boolean {
        while (true) {
            val suppressed = suppressedViewportUpdate.value ?: return false
            val matches = suppressed.page === page &&
                suppressed.areaSize == screenScaleState.areaSize.value &&
                suppressed.targetSize == screenScaleState.targetSize.value.toIntSize() &&
                suppressed.transformation == screenScaleState.transformation.value
            if (suppressedViewportUpdate.compareAndSet(suppressed, null)) return matches
        }
    }

    private fun viewportUpdateSnapshot(
        page: PanelsPage,
        screenScaleState: ScreenScaleState,
    ) = ViewportUpdateSnapshot(
        page = page,
        areaSize = screenScaleState.areaSize.value,
        targetSize = screenScaleState.targetSize.value.toIntSize(),
        transformation = screenScaleState.transformation.value,
    )

    private fun cancelPanelTransition() {
        panelTransitionController.cancel()
        screenScaleState.cancelScroll()
    }

    private fun scrollToPanel(
        imageSize: IntSize,
        screenSize: IntSize,
        targetSize: IntSize,
        panel: ImageRect,
    ) {
        val (offset, zoom) = getPanelOffsetAndZoom(
            imageSize = imageSize,
            areaSize = screenSize,
            targetSize = targetSize,
            panel = panel
        )
        screenScaleState.setZoom(zoom)
        screenScaleState.scrollTo(offset)
    }

    data class PanelsPage(
        val metadata: PageMetadata,
        val imageResult: ReaderImageResult?,
        val panelData: PanelData?,
    )

    data class PanelData(
        val panels: List<ImageRect>,
        val originalImageSize: IntSize,
        val panelCoversMajorityOfImage: Boolean,
    )

    data class PageIndex(
        val page: Int,
        val panel: Int,
        val isLastPanelZoomOutActive: Boolean,
    )

    private data class ReadyPanelsPage(
        val page: PanelsPage,
        val scale: ScreenScaleState,
    )

    private data class ViewportUpdateSnapshot(
        val page: PanelsPage,
        val areaSize: IntSize,
        val targetSize: IntSize,
        val transformation: ScreenScaleState.Transformation,
    )

    private data class RollingWindowKey(
        val image: ReaderImage,
        val panelIndex: Int,
        val areaSize: IntSize,
        val targetSize: IntSize,
    )

}
