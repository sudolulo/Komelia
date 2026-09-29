package snd.komelia.ui.reader.image.panels

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
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
import snd.komelia.image.PreparedUpdateLease
import snd.komelia.image.ReaderImage
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.ReaderImageResult
import snd.komelia.image.ViewportRenderRequirement
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

/**
 * One transition as this state observed it: the generation, mode, duration and movement timings
 * it owns, plus whether the retained page frame was there to move on.
 *
 * Every time is milliseconds elapsed since the input was accepted. A phase a transition never
 * reached reports the elapsed time at which the transition ended instead.
 *
 * Carries no book title, source URL, server URL, user identifier or credential.
 */
data class PanelTransitionEvent(
    val generation: Long,
    val mode: PanelTransitionMode,
    val durationMillis: Int,
    val outcome: PanelTransitionOutcome,
    /**
     * Whether the retained page frame was available, so the transition moved immediately. False
     * means it took the waiting fallback and rendered its destination before moving.
     */
    val pageFrameAvailable: Boolean,
    val readinessMillis: Long,
    val movementStartMillis: Long,
    val movementEndMillis: Long,
) {
    fun toLogLine(): String = "panel transition $generation $outcome mode=$mode " +
        "duration=${durationMillis}ms pageFrame=$pageFrameAvailable " +
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

    // The final animation value can reach the conflated observer after the controller returns to Idle.
    // Suppress only that exact duplicate of the explicit post-lease destination update.
    private val suppressedViewportUpdate = MutableStateFlow<ViewportUpdateSnapshot?>(null)
    private val resizeGeneration = MutableStateFlow(0L)
    private var resizeJob: Job? = null

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
        stateScope.coroutineContext.cancelChildren()
        pageLoadCoordinator.stop { currentPage.value = null }
        pendingPageIndex.value = null
        screenScaleState.enableOverscrollArea(false)
    }

    private suspend fun updateImageState(
        page: PanelsPage,
        screenScaleState: ScreenScaleState,
        force: Boolean = false,
    ) {
        if (!force && consumeSuppressedViewportUpdate(page, screenScaleState)) return

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

    private suspend fun prepareMaterialResize(
        page: PanelsPage,
        pageIndex: PageIndex,
        areaSize: IntSize,
        generation: Long,
    ) {
        val image = (page.imageResult as? ReaderImageResult.Success)?.image ?: return
        val panel = page.panelData?.panels?.getOrNull(pageIndex.panel) ?: return
        val isStillValid = {
            resizeGeneration.value == generation &&
                currentPage.value === page &&
                currentPageIndex.value == pageIndex &&
                screenScaleState.areaSize.value == areaSize
        }

        val fitState = getScaleFor(page, areaSize)
        if (!isStillValid()) return

        suppressedViewportUpdate.value = ViewportUpdateSnapshot(
            page = page,
            areaSize = areaSize,
            targetSize = fitState.targetSize.value.toIntSize(),
            transformation = fitState.transformation.value,
        )
        screenScaleState.apply(fitState)

        val targetSize = fitState.targetSize.value.toIntSize()
        if (targetSize.width <= 0 || targetSize.height <= 0) return
        val fitRequirement = ViewportRenderRequirement(
            region = IntRect(0, 0, targetSize.width, targetSize.height),
            zoomFactor = fitState.transformation.value.scale,
        )
        val lease = image.prepareUpdate(
            maxDisplaySize = areaSize,
            requirements = listOf(fitRequirement),
            isStillValid = isStillValid,
        )
        if (isStillValid()) {
            launchPanelTransition(pageIndex.panel, panel, preparedLease = lease)
        } else {
            lease.close()
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
        return launchPanelTransition(0, panels.first())
    }

    private fun launchPanelTransition(
        targetPanelIndex: Int,
        panel: ImageRect,
        preparedLease: PreparedUpdateLease? = null,
    ): Boolean {
        var leaseHandedOff = false
        try {
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

            val transition = PreparedPanelTransition(
                sourcePage = sourcePage,
                sourceIndex = sourceIndex,
                areaSize = areaSize,
                targetSize = targetSize,
                targetPanelIndex = targetPanelIndex,
                targetZoom = target.targetZoom,
                targetOffset = target.targetOffset,
                requirements = target.requirements,
            )

            // every field of the event is owned by this layer, so the record is assembled here
            val generation = transitionGeneration.updateAndGet { it + 1 }
            val transitionStart = TimeSource.Monotonic.markNow()
            var pageFrameAvailable = false
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
                        pageFrameAvailable = pageFrameAvailable,
                        readinessMillis = readinessMillis ?: elapsedMillis,
                        movementStartMillis = movementStartMillis ?: elapsedMillis,
                        movementEndMillis = movementEndMillis ?: elapsedMillis,
                    )
                )
            }

            val launched = panelTransitionController.launch(
                prepare = {
                    try {
                        // the page frame covers the whole page, so publishing it is all the movement
                        // needs; the lease that comes back is a publication lock and renders nothing
                        val lease = if (preparedLease != null) {
                            pageFrameAvailable = true
                            readinessMillis = 0L
                            preparedLease
                        } else {
                            val pageFrame = image.publishPageFrame()
                            if (pageFrame != null) {
                                pageFrameAvailable = true
                                readinessMillis = 0L
                                pageFrame
                            } else {
                                // no frame covers the page yet, so moving now would sweep across
                                // unrendered space: this is the only case where movement waits
                                logger.info {
                                    "panel transition $generation has no page frame, " +
                                        "rendering ${transition.requirements.destination.region} before moving"
                                }
                                val renderedLease = image.prepareUpdate(
                                    maxDisplaySize = transition.areaSize,
                                    requirements = listOf(transition.requirements.destination),
                                )
                                readinessMillis = transitionStart.elapsedNow().inWholeMilliseconds
                                renderedLease
                            }
                        }
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
                        updateImageState(page, screenScaleState, force = true)
                    }
                },
            )
            leaseHandedOff = launched
            return launched
        } finally {
            if (!leaseHandedOff) preparedLease?.close()
        }
    }

    /**
     * Resolves what a transition to [panel] has to render and where it ends up, or null when the
     * geometry is not usable yet.
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

        val targetZoomStartTransformation = screenScaleState.transformationForZoom(targetZoom)
        val targetTransformation = ScreenScaleState.Transformation(
            offset = targetOffset,
            scale = targetZoomStartTransformation.scale,
        )

        return try {
            PanelTarget(
                requirements = panelTransitionRequirements(
                    targetSize = targetSize,
                    areaSize = areaSize,
                    targetTransformation = targetTransformation,
                ),
                targetZoom = targetZoom,
                targetOffset = targetOffset,
            )
        } catch (e: IllegalArgumentException) {
            // a degenerate viewport has nothing to render; the caller keeps its current frame
            logger.debug(e) { "panel transition geometry is not renderable" }
            null
        }
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

}
