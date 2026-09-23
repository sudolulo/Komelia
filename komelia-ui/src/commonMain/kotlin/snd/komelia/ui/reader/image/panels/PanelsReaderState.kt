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
import snd.komelia.image.PreparedTransitionCoverage
import snd.komelia.image.PreparedUpdateLease
import snd.komelia.image.ReaderImage
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.ReaderImageResult
import snd.komelia.image.TransitionRenderStats
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
import kotlin.concurrent.Volatile
import kotlin.math.max
import kotlin.math.min
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger { }

internal fun PanelTransitionMode.durationMillis(): Int = when (this) {
    PanelTransitionMode.SNAPPY -> SNAPPY_TRANSITION_DURATION_MS
    PanelTransitionMode.CLASSIC -> CLASSIC_TRANSITION_DURATION_MS
}

/** How one panel transition ended. Exactly one outcome is reported per accepted transition. */
enum class PanelTransitionOutcome {
    /** the swept corridor was covered and the movement animation ran to completion */
    ANIMATED,

    /** only the endpoints were covered, so the destination was reached without animation */
    JUMPED,

    /** not even the endpoints fit the render budget; frame and transform were preserved */
    REJECTED,

    /** the reader moved on before the prepared coverage could be used */
    SUPERSEDED,

    /** the transition was cancelled before it finished */
    CANCELLED,

    /** the transition failed; the throwable itself is reported through the error path */
    FAILED,
}

/**
 * One transition, correlated across both layers: the generation, mode, duration and movement
 * timings this state owns, joined with what the renderer reported it did.
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
    val requirementCount: Int,
    val stats: TransitionRenderStats?,
    val readinessMillis: Long,
    val movementStartMillis: Long,
    val movementEndMillis: Long,
) {
    /**
     * Native tile renders the demand path issued before movement, or null when the renderer
     * does not account for pixels. Null is never reported as zero: zero is the success case.
     */
    val renderedTiles: Int? get() = stats?.renderedTiles

    fun toLogLine(): String = "panel transition $generation $outcome mode=$mode " +
        "duration=${durationMillis}ms requirements=$requirementCount " +
        "readiness=${readinessMillis}ms " +
        "movement=${movementStartMillis}ms..${movementEndMillis}ms stats=${statsField()}"

    private fun statsField(): String {
        val stats = stats ?: return "unavailable"
        return "[rendered=${stats.renderedTiles} reused=${stats.reusedTiles} " +
            "upgraded=${stats.upgradedTiles} evicted=${stats.evictedTiles} " +
            "retainedPixels=${stats.uniqueRetainedPixels} reservedPixels=${stats.reservedPixels}]"
    }
}

/** Where [PanelTransitionEvent]s go. The default sink logs them. */
fun interface PanelTransitionDiagnostics {
    fun onTransition(event: PanelTransitionEvent)

    companion object {
        val Logging = PanelTransitionDiagnostics { event -> logger.info { event.toLogLine() } }
    }
}

private fun PreparedTransitionCoverage.statsOrNull(): TransitionRenderStats? = when (this) {
    is PreparedTransitionCoverage.Animated -> stats
    is PreparedTransitionCoverage.EndpointOnly -> stats
    PreparedTransitionCoverage.Rejected -> null
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

    /** generation-scoped neighbour prefetch; replaced or cancelled whenever its inputs change */
    @Volatile
    private var prefetchJob: Job? = null
    // The final animation value can reach the conflated observer after the controller returns to Idle.
    // Suppress only that exact duplicate of the explicit post-lease destination update.
    private val suppressedViewportUpdate = MutableStateFlow<ViewportUpdateSnapshot?>(null)

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
                        // the viewport this prefetch was keyed on just changed
                        scheduleAdjacentPrefetch()
                    }
                }
                delay(100)
            }
            .launchIn(stateScope)

        screenScaleState.areaSize
            .drop(1)
            .conflate()
            .onEach {
                // the fitted geometry changed, so nothing keyed on the old one is reusable
                cancelAdjacentPrefetch()
                when (panelTransitionController.phase.value) {
                    PanelTransitionPhase.Animating -> cancelPanelTransition()
                    PanelTransitionPhase.Preparing -> {
                        cancelPanelTransition()
                        currentPage.value?.let { page ->
                            updateImageState(page, screenScaleState)
                            scheduleAdjacentPrefetch()
                        }
                    }

                    PanelTransitionPhase.Idle -> {
                        currentPage.value?.let { page ->
                            updateImageState(page, screenScaleState)
                            scheduleAdjacentPrefetch()
                        }
                    }
                }
                delay(100)
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
                scheduleAdjacentPrefetch()
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

    private suspend fun getScaleFor(
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

        val panels = page.panelData?.panels
        if (panels.isNullOrEmpty()) {
            scaleState.setZoom(0f)
        } else {
            val firstPanel = panels.first()
            val imageSize = image.getOriginalImageSize().getOrNull() ?: return defaultScale
            val (offset, zoom) = getPanelOffsetAndZoom(
                imageSize = imageSize,
                areaSize = containerSize,
                targetSize = fitToScreenSize,
                panel = firstPanel
            )
            scaleState.setZoom(zoom)
            scaleState.setOffset(offset)
        }

        return scaleState
    }

    private fun scrollToFit() {
//        val areaSize = screenScaleState.areaSize.value
//        val startX = 0 - areaSize.width.toFloat()
//        val startY = 0 - areaSize.height.toFloat()
        screenScaleState.setZoom(0f)
        screenScaleState.scrollTo(Offset(0f, 0f))

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

        // the neighbour prefetch was keyed on the viewport this transition is about to leave
        cancelAdjacentPrefetch()

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
        var coverage: PreparedTransitionCoverage = PreparedTransitionCoverage.Rejected

        // the renderer reports what it rendered; every other field of the event lives here, so
        // the correlated record can only be assembled at this layer
        val generation = transitionGeneration.updateAndGet { it + 1 }
        val transitionStart = TimeSource.Monotonic.markNow()
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
                    requirementCount = transition.requirements.animatedRequirements.size,
                    stats = coverage.statsOrNull(),
                    readinessMillis = readinessMillis ?: elapsedMillis,
                    movementStartMillis = movementStartMillis ?: elapsedMillis,
                    movementEndMillis = movementEndMillis ?: elapsedMillis,
                )
            )
        }

        return panelTransitionController.launch(
            prepare = {
                try {
                    val prepared = image.prepareTransitionUpdate(
                        maxDisplaySize = transition.areaSize,
                        animatedRequirements = transition.requirements.animatedRequirements,
                        endpointRequirements = transition.requirements.endpointRequirements,
                    )
                    coverage = prepared
                    readinessMillis = transitionStart.elapsedNow().inWholeMilliseconds
                    when (prepared) {
                        is PreparedTransitionCoverage.Animated -> prepared.lease
                        is PreparedTransitionCoverage.EndpointOnly -> prepared.lease
                        // nothing was rendered or published, so there is no coverage to hold
                        PreparedTransitionCoverage.Rejected -> {
                            emitTransitionEvent(PanelTransitionOutcome.REJECTED)
                            PreparedUpdateLease {}
                        }
                    }
                } catch (e: CancellationException) {
                    emitTransitionEvent(PanelTransitionOutcome.CANCELLED)
                    throw e
                } catch (e: Throwable) {
                    emitTransitionEvent(PanelTransitionOutcome.FAILED)
                    throw e
                }
            },
            // a rejected transition must not activate: no movement, and no reconciliation either
            isStillValid = {
                val valid = coverage != PreparedTransitionCoverage.Rejected &&
                    currentPage.value === transition.sourcePage &&
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

                    if (coverage is PreparedTransitionCoverage.EndpointOnly) {
                        // only the endpoints are covered, so the path between them must not be drawn
                        logger.info {
                            "panel transition jumped to ${transition.requirements.destination.region}: " +
                                "animated coverage did not fit the render budget"
                        }
                        screenScaleState.setOffset(transition.targetOffset)
                        movementEndMillis = transitionStart.elapsedNow().inWholeMilliseconds
                        emitTransitionEvent(PanelTransitionOutcome.JUMPED)
                        return@launch
                    }

                    logger.info {
                        "panel transition coverage ${transition.requirements.swept.region} ready at " +
                            "scale ${transition.requirements.targetTransformation.scale}"
                    }
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
                scheduleAdjacentPrefetch()
            },
        )
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
                    sourceTransformation = sourceTransformation,
                    targetZoomStartTransformation = targetZoomStartTransformation,
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

    /**
     * Fills the coverage the immediate reading-direction neighbour needs, then the opposite one,
     * so a transition the user is about to ask for finds its tiles already rendered.
     *
     * Best effort and generation scoped: any page, image or geometry change cancels it, and a
     * failure is logged rather than surfaced, because nothing on screen depends on it.
     */
    private fun scheduleAdjacentPrefetch() {
        cancelAdjacentPrefetch()

        val page = currentPage.value ?: return
        val panelData = page.panelData ?: return
        val image = (page.imageResult as? ReaderImageResult.Success)?.image ?: return
        val panelIndex = currentPageIndex.value.panel
        val areaSize = screenScaleState.areaSize.value
        val targetSize = screenScaleState.targetSize.value.toIntSize()
        val sourceTransformation = screenScaleState.transformation.value

        val neighbours = listOfNotNull(
            panelData.panels.getOrNull(panelIndex + 1),
            panelData.panels.getOrNull(panelIndex - 1),
        )
        if (neighbours.isEmpty()) return

        prefetchJob = stateScope.launch {
            for (neighbour in neighbours) {
                val target = panelTarget(
                    panelData = panelData,
                    areaSize = areaSize,
                    targetSize = targetSize,
                    sourceTransformation = sourceTransformation,
                    panel = neighbour,
                ) ?: continue

                try {
                    image.prefetchUpdate(
                        maxDisplaySize = areaSize,
                        requirements = target.requirements.animatedRequirements,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    logger.warn(e) { "panel neighbour prefetch failed" }
                    return@launch
                }
            }
        }
    }

    private fun cancelAdjacentPrefetch() {
        prefetchJob?.cancel()
        prefetchJob = null
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
        cancelAdjacentPrefetch()
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

    private fun getPanelOffsetAndZoom(
        imageSize: IntSize,
        areaSize: IntSize,
        targetSize: IntSize,
        panel: ImageRect,
    ): Pair<Offset, Float> {
        val xScale: Float = targetSize.width.toFloat() / imageSize.width
        val yScale: Float = targetSize.height.toFloat() / imageSize.height

        val bboxLeft: Float = panel.left.coerceAtLeast(0) * xScale
        val bboxRight: Float = panel.right.coerceAtMost(imageSize.width) * xScale
        val bboxBottom: Float = panel.bottom.coerceAtMost(imageSize.height) * yScale
        val bboxTop: Float = panel.top.coerceAtLeast(0) * yScale
        val bboxWidth: Float = bboxRight - bboxLeft
        val bboxHeight: Float = bboxBottom - bboxTop

        val scale: Float = min(
            areaSize.width / bboxWidth,
            areaSize.height / bboxHeight
        )
        val fitToScreenScale = max(
            areaSize.width.toFloat() / targetSize.width,
            areaSize.height.toFloat() / targetSize.height
        )
        val zoom: Float = scale / fitToScreenScale

        val bboxHalfWidth: Float = bboxWidth / 2.0f
        val bboxHalfHeight: Float = bboxHeight / 2.0f
        val imageHalfWidth: Float = targetSize.width / 2.0f
        val imageHalfHeight: Float = targetSize.height / 2.0f

        val centerX: Float = (bboxLeft - imageHalfWidth) * -1.0f
        val centerY: Float = (bboxTop - imageHalfHeight) * -1.0f
        val offset = Offset(
            (centerX - bboxHalfWidth) * scale,
            (centerY - bboxHalfHeight) * scale
        )

        return offset to zoom
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
