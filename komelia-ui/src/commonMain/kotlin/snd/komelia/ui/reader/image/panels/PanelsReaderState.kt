package snd.komelia.ui.reader.image.panels

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toIntSize
import androidx.compose.ui.unit.toSize
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
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
import snd.komelia.ui.reader.image.BookState
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.ReaderState
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage.BookEnd
import snd.komelia.ui.reader.image.paged.PagedReaderState.TransitionPage.BookStart
import snd.komga.client.common.KomgaReadingDirection
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.measureTimedValue

private val logger = KotlinLogging.logger { }

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
) {
    private val stateScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pageLoadCoordinator = PageLoadCoordinator<PageId, PanelsPage>(
        cleanupScope = cleanupScope,
        closeValue = { it.imageResult?.image?.close() },
    )
    private val pendingPageIndex = MutableStateFlow<Int?>(null)

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

        combine(
            screenScaleState.transformation,
            screenScaleState.areaSize,
        ) {}
            .drop(1).conflate()
            .onEach {
                currentPage.value?.let { page ->
                    updateImageState(page, screenScaleState)
                    delay(100)
                }
            }
            .launchIn(stateScope)

        readingDirection.drop(1).onEach { readingDirection ->
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
        stateScope.coroutineContext.cancelChildren()
        pageLoadCoordinator.stop { currentPage.value = null }
        pendingPageIndex.value = null
        screenScaleState.enableOverscrollArea(false)
    }

    private suspend fun updateImageState(
        page: PanelsPage,
        screenScaleState: ScreenScaleState,
    ) {
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
        this.readingDirection.value = readingDirection
        stateScope.launch { settingsRepository.putPagedReaderReadingDirection(readingDirection) }
    }


    fun nextPanel() {
        if (pendingPageIndex.value != null) return
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
        val nextPanel = panels[panelIndex + 1]
        val areaSize = screenScaleState.areaSize.value
        val targetSize = IntSize(
            screenScaleState.targetSize.value.width.roundToInt(),
            screenScaleState.targetSize.value.height.roundToInt()
        )
        val imageSize = currentPage.panelData.originalImageSize
        scrollToPanel(
            imageSize = imageSize,
            screenSize = areaSize,
            targetSize = targetSize,
            panel = nextPanel
        )
        currentPageIndex.update { it.copy(panel = panelIndex + 1) }
    }

    private fun nextPage() {
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
        if (pendingPageIndex.value != null) return
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
        val previousPage = panels[panelIndex - 1]
        val areaSize = screenScaleState.areaSize.value
        val targetSize = IntSize(
            screenScaleState.targetSize.value.width.roundToInt(),
            screenScaleState.targetSize.value.height.roundToInt()
        )
        val imageSize = currentPage.panelData.originalImageSize
        scrollToPanel(
            imageSize = imageSize,
            screenSize = areaSize,
            targetSize = targetSize,
            panel = previousPage
        )
        currentPageIndex.update {
            it.copy(panel = panelIndex - 1, isLastPanelZoomOutActive = false)
        }
    }

    private fun previousPage() {
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
            afterReady = { preloadImagesBetween(pageIndex) },
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

}
