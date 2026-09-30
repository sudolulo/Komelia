package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import snd.komelia.image.processing.ImageProcessingPipeline
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.TimeSource

// Rendering is admitted against a fixed number of fitted display areas before a tiled frame is
// published. This keeps multi-viewport updates bounded without reducing requested resolution.
private const val tileThreshold1 = 2048 * 2048
private const val tileThreshold2 = 4096 * 4096
private const val tileThreshold3 = 6144 * 6144

/**
 * A render input change invalidates an in-flight pass and the pass restarts. Restarting forever
 * would hide a thrashing settings flow, so a bounded number of restarts is allowed per request.
 */
private const val maxGenerationRetries = 8

/**
 * How far, per axis in display pixels, the fitted display size may drift from the one the
 * retained page frame was rendered at before the frame is discarded. Compose layout passes
 * report areas a pixel or two apart and calculateSizeForArea rounds, so the fitted size jitters
 * without anything visibly changing; a fold, rotation or window resize moves it by hundreds.
 */
private const val maxToleratedDisplayDrift = 8

internal fun visibilityWindowFor(displayRegion: Rect, displayArea: IntSize): Rect {
    val horizontalMargin = displayRegion.width.coerceAtLeast(0f) / 2f
    val verticalMargin = displayRegion.height.coerceAtLeast(0f) / 2f
    val maxRight = displayArea.width.coerceAtLeast(0).toFloat()
    val maxBottom = displayArea.height.coerceAtLeast(0).toFloat()

    return Rect(
        left = (displayRegion.left - horizontalMargin).coerceIn(0f, maxRight),
        top = (displayRegion.top - verticalMargin).coerceIn(0f, maxBottom),
        right = (displayRegion.right + horizontalMargin).coerceIn(0f, maxRight),
        bottom = (displayRegion.bottom + verticalMargin).coerceIn(0f, maxBottom),
    )
}


expect class RenderImage

private val logger = KotlinLogging.logger {}

abstract class TilingReaderImage(
    private val imageSource: ImageSource,
    private val imageDecoder: KomeliaImageDecoder,
    private val processingPipeline: ImageProcessingPipeline,
    private val stretchImages: StateFlow<Boolean>,
    protected val upsamplingMode: StateFlow<UpsamplingMode>,
    protected val downSamplingKernel: StateFlow<ReduceKernel>,
    protected val linearLightDownSampling: StateFlow<Boolean>,
    final override val pageId: ReaderImage.PageId,
    protected val hardPeakDisplayAreas: Int = 8,
) : ReaderImage {
    final override val painter = MutableStateFlow<TiledPainter?>(null)
    final override val error = MutableStateFlow<Throwable?>(null)

    final override val originalSize = MutableStateFlow<IntSize?>(null)
    final override val displaySize = MutableStateFlow<IntSize?>(null)
    final override val currentSize = MutableStateFlow<IntSize?>(null)

    private val imageAwaitScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val animationScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
    protected val processingScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())

    /** Owns the single processing lane that serializes demand rendering. */
    private val arbiter = ImageUpdateArbiter(processingScope)

    private val jobFlow = MutableSharedFlow<UpdateRequest>(1, 0, BufferOverflow.DROP_OLDEST)
    private val frameData = MutableStateFlow<FrameData?>(null)
    protected val image = MutableStateFlow<KomeliaImage?>(null)
    protected val defaultFrameDelay = 100L

    /** Tiles the renderer keeps alive between passes for sufficient-resolution reuse. */
    private val retainedTiles = MutableStateFlow<Map<TileCacheKey, RetainedTile>>(emptyMap())

    /** Fit-only full page storage. Ordinary full resizes never mutate this slot. */
    private val retainedFitBase = MutableStateFlow<RetainedFitPageBase?>(null)

    /** Accounts for every native bitmap and in-flight reservation owned by this reader image. */
    private val pixelLedger = TilePixelLedger(0)

    internal val retainedFitPageBase: RetainedFitPageBase?
        get() = retainedFitBase.value

    /**
     * Whether a fit-zoom page frame rendered for the current render generation is held. A frame
     * from an older generation belongs to geometry or raster inputs that no longer apply, so it
     * is not available even in the window before its release runs.
     */
    internal val hasRetainedPageFrame: Boolean
        get() = retainedFitBase.value?.generation == renderGeneration

    /** Mutated only from the processing lane. */
    private var renderGeneration = 0L

    // all of the fields below are mutated only from the single threaded processing scope
    private var renderFingerprint: RenderFingerprint? = null
    private var imageEpoch = 0L
    private var rendererEpoch = 0L
    private var lastUsedScaleFactor: Double? = null

    @Volatile
    private var originalImage: KomeliaImage? = null

    @Volatile
    protected var lastUpdateRequest: UpdateRequest? = null

    data class UpdateRequest(
        val requirements: List<ViewportRenderRequirement>,
        val maxDisplaySize: IntSize,
        val readiness: CompletableDeferred<Unit>? = null,
    ) {
        init {
            require(requirements.isNotEmpty()) { "an update request needs at least one viewport requirement" }
        }
    }

    init {
        jobFlow.conflate()
            .onEach { request ->
                // the conflated viewport path has no caller to report accounting to
                arbiter.submitDemand { processUpdate(request, RenderPassStats()) }
                delay(50)
            }.launchIn(processingScope)

        processingPipeline.changeFlow.onEach {
            val currentImage = image.value
            if (currentImage !== originalImage) {
                currentImage?.close()
            }

            imageEpoch += 1
            // the page frame draws the image being replaced, so it is released here rather
            // than waiting for the next render pass to notice the new generation
            releaseRetainedFitBase()
            image.value = null
            originalSize.value = originalImage?.let { IntSize(it.width, it.pageHeight) }
            currentSize.value = null
            loadImage()
            reloadLastRequest()
        }.launchIn(processingScope)

        stretchImages.drop(1).onEach { reloadLastRequest() }.launchIn(processingScope)
        upsamplingMode.onEach { mode -> painter.update { it?.withSamplingMode(mode) } }
            .launchIn(processingScope)
        downSamplingKernel.drop(1).onEach { reloadLastRequest() }
            .launchIn(processingScope)
        linearLightDownSampling.drop(1).onEach { reloadLastRequest() }
            .launchIn(processingScope)

        processingScope.launch { loadImage() }

        frameData.onEach { data ->
            try {
                when {
                    data == null -> this.painter.value = null
                    data.frames.size == 1 -> {
                        val nextPainter = createTilePainter(
                            tiles = data.frames.first().tiles,
                            displaySize = data.displaySize,
                            scaleFactor = data.scaleFactor
                        )
                        currentCoroutineContext().ensureActive()
                        this.painter.value = nextPainter
                        // the retired frame stays alive until its replacement is actually visible
                        data.releaseRetired()
                        data.readiness?.complete(Unit)
                    }

                    else -> launchAnimation(data)
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                logger.catching(e)
                this.error.value = e
                data?.readiness?.completeExceptionally(e)
            }
        }.launchIn(processingScope)
    }

    private fun launchAnimation(data: FrameData) {
        animationScope.coroutineContext.cancelChildren()
        animationScope.launch {
            try {
                val painters = data.frames.map {
                    createTilePainter(
                        tiles = it.tiles,
                        displaySize = data.displaySize,
                        scaleFactor = data.scaleFactor
                    )
                }
                var firstPainterPublished = false

                while (isActive) {
                    for ((index, tiledPainter) in painters.withIndex()) {
                        val frameDelay = data.frames[index].delay
                        this@TilingReaderImage.painter.value = tiledPainter
                        if (!firstPainterPublished) {
                            data.releaseRetired()
                            data.readiness?.complete(Unit)
                            firstPainterPublished = true
                        }
                        delay(if (frameDelay < 10) defaultFrameDelay else frameDelay)
                    }
                }
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                logger.catching(e)
                this@TilingReaderImage.error.value = e
                data.readiness?.completeExceptionally(e)
            }
        }
    }

    private suspend fun processUpdate(request: UpdateRequest, stats: RenderPassStats) {
        try {
            processUpdateLocked(request, stats) { true }
            error.value = null
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.catching(e)
            error.value = e
            request.readiness?.completeExceptionally(e)
        }
    }

    private suspend fun processUpdateLocked(
        request: UpdateRequest,
        stats: RenderPassStats,
        isStillValid: () -> Boolean,
    ) {
        val publishedFrame = doUpdate(request, stats, isStillValid)
        if (!publishedFrame) {
            request.readiness?.complete(Unit)
        } else {
            request.readiness?.await()
        }
    }

    protected suspend fun reloadLastRequest() {
        lastUpdateRequest?.let { lastRequest ->
            jobFlow.emit(lastRequest)
        }
    }

    override suspend fun getOriginalImageSize(): Result<IntSize> {
        return coroutineScope {
            select {
                async { originalSize.filterNotNull().first() }.onAwait { Result.success(it) }
                async { error.filterNotNull().first() }.onAwait { Result.failure(it) }
            }.also { coroutineContext.cancelChildren() }
        }
    }

    override suspend fun getOriginalImage(): Result<KomeliaImage> {
        return coroutineScope {
            select {
                async { image.filterNotNull().first() }.onAwait { Result.success(it) }
                async { error.filterNotNull().first() }.onAwait { Result.failure(it) }
            }.also { coroutineContext.cancelChildren() }
        }
    }

    private suspend fun loadImage() {
        try {
            val originalImage = decodeImage(imageSource)
            this.originalImage = originalImage
            val processed = processingPipeline.process(pageId, originalImage)
            imageEpoch += 1
            image.value = processed
            originalSize.value = IntSize(processed.width, processed.pageHeight)
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.catching(e)
            this.error.value = e
            imageAwaitScope.coroutineContext.cancelChildren()
        }
    }

    private suspend fun decodeImage(source: ImageSource): KomeliaImage {
        val image = when (source) {
            is ImageSource.FilePathSource -> imageDecoder.decodeFromFile(source.path)
            is ImageSource.MemorySource -> imageDecoder.decode(source.data)
        }
        return if (image.pagesTotal != 1) {
            image.close()
            when (source) {
                is ImageSource.FilePathSource -> imageDecoder.decodeFromFile(source.path, -1)
                is ImageSource.MemorySource -> imageDecoder.decode(source.data, -1)
            }
        } else {
            image
        }

    }

    private suspend fun getCurrentImage(): KomeliaImage {
        return imageAwaitScope.async { image.filterNotNull().first() }.await()
    }

    override fun requestUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ) {
        // the conflated viewport path is fire and forget: an empty or degenerate viewport has
        // nothing to render, and throwing here would take down the caller's frame instead
        if (!zoomFactor.isFinite() || zoomFactor <= 0f ||
            visibleDisplaySize.width <= 0 || visibleDisplaySize.height <= 0
        ) {
            logger.debug {
                "page ${pageId.pageNumber} ignored update request " +
                    "for $visibleDisplaySize at zoom $zoomFactor"
            }
            return
        }

        jobFlow.tryEmit(
            UpdateRequest(
                requirements = listOf(ViewportRenderRequirement(visibleDisplaySize, zoomFactor)),
                maxDisplaySize = maxDisplaySize,
            )
        )
    }

    final override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease = prepareUpdateWithStats(maxDisplaySize, requirements) { true }.lease

    final override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
        isStillValid: () -> Boolean,
    ): PreparedUpdateLease = prepareUpdateWithStats(maxDisplaySize, requirements, isStillValid).lease

    /**
     * [prepareUpdate] plus the accounting of what the pass actually did, for callers that
     * report render work rather than only consume its result.
     */
    private suspend fun prepareUpdateWithStats(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
        isStillValid: () -> Boolean,
    ): PreparedUpdateResult {
        require(requirements.isNotEmpty()) { "prepareUpdate needs at least one viewport requirement" }
        val readiness = CompletableDeferred<Unit>()
        val lease = PreparedUpdateLeaseImpl()
        val prepared = CompletableDeferred<PreparedUpdateResult>()
        val stats = RenderPassStats()
        val job = processingScope.launch {
            // the lane is held for the whole lease lifetime, so no other update can publish
            // over the prepared coverage while a caller still depends on it
            arbiter.submitDemand {
                var leaseOwnership: List<TileOwnership> = emptyList()
                try {
                    val request = UpdateRequest(
                        requirements = requirements,
                        maxDisplaySize = maxDisplaySize,
                        readiness = readiness,
                    )
                    ensureUpdateIsCurrent(isStillValid)
                    processUpdateLocked(request, stats, isStillValid)
                    ensureUpdateIsCurrent(isStillValid)
                    error.value = null
                    // the lease owns the exact handle set that satisfied readiness
                    leaseOwnership = frameData.value?.retainHandles(pixelLedger) ?: emptyList()
                    prepared.complete(
                        PreparedUpdateResult(
                            lease,
                            stats.toTransitionStats(pixelLedger.snapshot().livePixels),
                        )
                    )
                    lease.awaitRelease()
                } catch (e: CancellationException) {
                    readiness.completeExceptionally(e)
                    prepared.completeExceptionally(e)
                    throw e
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    logger.catching(e)
                    error.value = e
                    readiness.completeExceptionally(e)
                    prepared.completeExceptionally(e)
                } finally {
                    leaseOwnership.forEach { it.close() }
                }
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                readiness.completeExceptionally(cause)
                prepared.completeExceptionally(cause)
            }
        }
        try {
            return prepared.await()
        } catch (e: Throwable) {
            lease.close()
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    /**
     * Installs the retained fit-zoom page frame and holds the processing lane until the returned
     * lease is closed, which is what keeps an ordinary viewport update from publishing a tiled
     * frame over it mid movement.
     *
     * Nothing is rendered here and the lease holds no rendered coverage of its own: the frame
     * already exists and stays alive through its own long-lived retention.
     */
    final override suspend fun publishPageFrame(): PreparedUpdateLease? {
        val lease = PreparedUpdateLeaseImpl()
        val published = CompletableDeferred<Boolean>()
        val job = processingScope.launch {
            // publication renders nothing, so it may jump the queue of waiting render work;
            // every other submitDemand call site stays ordinary FIFO
            arbiter.submitDemand(priority = true) {
                var frameOwnership: List<TileOwnership> = emptyList()
                try {
                    val page = retainedFitBase.value?.takeIf { it.generation == renderGeneration }
                    if (page == null) {
                        logger.debug { "page ${pageId.pageNumber} has no page frame to publish" }
                        published.complete(false)
                        return@submitDemand
                    }

                    frameOwnership = page.handles.mapNotNull { pixelLedger.retain(it) }
                    if (frameOwnership.size != page.handles.size) {
                        // the frame died between the availability check and the reference
                        logger.warn { "page ${pageId.pageNumber} lost its page frame before publication" }
                        published.complete(false)
                        return@submitDemand
                    }

                    val readiness = CompletableDeferred<Unit>()
                    frameData.value = FrameData(
                        frames = page.frames,
                        displaySize = page.displaySize,
                        scaleFactor = fitScaleFactor(page.displaySize, getCurrentImage()),
                        readiness = readiness,
                        handles = page.handles,
                        frameOwnership = frameOwnership,
                        retiredOwnership = frameData.value?.takePendingOwnership() ?: emptyList(),
                    )
                    // the frame owns those references now, so the failure path must not close them
                    frameOwnership = emptyList()
                    readiness.await()
                    // the installed frame is the fit-zoom full resize again
                    lastUsedScaleFactor = fitScaleFactor(page.displaySize, getCurrentImage())
                    published.complete(true)
                    lease.awaitRelease()
                } catch (e: Throwable) {
                    currentCoroutineContext().ensureActive()
                    logger.catching(e)
                    error.value = e
                    published.completeExceptionally(e)
                } finally {
                    frameOwnership.forEach { it.close() }
                }
            }
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) published.completeExceptionally(cause)
        }
        try {
            return if (published.await()) lease else null
        } catch (e: Throwable) {
            lease.close()
            currentCoroutineContext().ensureActive()
            throw e
        }
    }

    private class PreparedUpdateResult(
        val lease: PreparedUpdateLease,
        val stats: TransitionRenderStats,
    )

    /**
     * Accumulates what one demand-path call did across every attempt it needed, including the
     * passes a mid-flight render input change made stale. Mutated only from the processing lane.
     */
    private class RenderPassStats {
        var reusedTiles = 0
        var upgradedTiles = 0
        var renderedTiles = 0
        var evictedTiles = 0

        fun toTransitionStats(uniqueRetainedPixels: Long) = TransitionRenderStats(
            reusedTiles = reusedTiles,
            upgradedTiles = upgradedTiles,
            renderedTiles = renderedTiles,
            evictedTiles = evictedTiles,
            uniqueRetainedPixels = uniqueRetainedPixels,
        )
    }

    private class PreparedUpdateLeaseImpl : PreparedUpdateLease {
        private val released = CompletableDeferred<Unit>()

        override fun close() {
            released.complete(Unit)
        }

        suspend fun awaitRelease() {
            released.await()
        }
    }

    private suspend fun doUpdate(
        request: UpdateRequest,
        stats: RenderPassStats,
        isStillValid: () -> Boolean,
    ): Boolean {
        ensureUpdateIsCurrent(isStillValid)

        if (request.maxDisplaySize.width <= 0 || request.maxDisplaySize.height <= 0) {
            logger.debug { "page ${pageId.pageNumber} skipped update for empty area ${request.maxDisplaySize}" }
            return false
        }

        val image = getCurrentImage()
        val displaySize = calculateSizeForArea(request.maxDisplaySize, stretchImages.value) ?: return false
        if (displaySize.width <= 0 || displaySize.height <= 0) {
            logger.debug { "page ${pageId.pageNumber} skipped update for empty display size $displaySize" }
            return false
        }

        val widthRatio = displaySize.width.toDouble() / image.width
        val heightRatio = displaySize.height.toDouble() / image.pageHeight
        val displayScaleFactor = widthRatio.coerceAtMost(heightRatio)
        if (displayScaleFactor <= 0.0) {
            logger.debug { "page ${pageId.pageNumber} skipped update for display scale $displayScaleFactor" }
            return false
        }

        // the frame must be able to serve the densest requirement it covers
        val zoomFactor = request.requirements.maxOf { it.zoomFactor }
        val actualScaleFactor = displayScaleFactor * zoomFactor

        val dstWidth = displaySize.width * zoomFactor
        val dstHeight = displaySize.height * zoomFactor

        ensureUpdateIsCurrent(isStillValid)
        lastUpdateRequest = request.copy(readiness = null)
        this.displaySize.value = displaySize
        this.currentSize.value = IntSize(dstWidth.roundToInt(), dstHeight.roundToInt())

        val tileSize = tileSizeFor((dstWidth * dstHeight).roundToInt())

        repeat(maxGenerationRetries) {
            val generation = generationFor(displaySize)
            pixelLedger.setHardPeakPixels(hardPeakPixelsFor(displaySize))
            val result = if (image.pagesLoaded > 1 || tileSize == null) {
                doFullResize(
                    image = image,
                    scaleFactor = actualScaleFactor,
                    displayScaleFactor = displayScaleFactor,
                    displayArea = displaySize,
                    generation = generation,
                    readiness = request.readiness,
                    stats = stats,
                    isStillValid = isStillValid,
                )
            } else {
                doTile(
                    image = image,
                    requirements = request.requirements,
                    displayScaleFactor = displayScaleFactor,
                    scaleFactor = actualScaleFactor,
                    displayArea = displaySize,
                    tileSize = tileSize,
                    generation = generation,
                    readiness = request.readiness,
                    stats = stats,
                    isStillValid = isStillValid,
                )
            }

            when (result) {
                UpdateResult.PUBLISHED -> return true
                UpdateResult.UNCHANGED -> return false
                UpdateResult.STALE -> logger.debug {
                    "page ${pageId.pageNumber} discarded render generation $generation output"
                }
            }
        }

        throw IllegalStateException(
            "page ${pageId.pageNumber} render inputs changed during " +
                "$maxGenerationRetries consecutive render attempts"
        )
    }

    private suspend fun doFullResize(
        image: KomeliaImage,
        scaleFactor: Double,
        displayScaleFactor: Double,
        displayArea: IntSize,
        generation: Long,
        readiness: CompletableDeferred<Unit>?,
        stats: RenderPassStats,
        isStillValid: () -> Boolean,
    ): UpdateResult {
        if (lastUsedScaleFactor == scaleFactor) {
            error.value?.let { throw (it) }
            return UpdateResult.UNCHANGED
        }

        val dstWidth = checkedRenderDimension(image.width, scaleFactor)
        val dstHeight = checkedRenderDimension(image.pageHeight, scaleFactor)
        val requiredSize = IntSize(dstWidth, dstHeight)
        val sourceBounds = IntRect(0, 0, image.width, image.pageHeight)
        val tileDisplayRegion = Rect(
            0f,
            0f,
            round(image.width * displayScaleFactor).toFloat(),
            round(image.pageHeight * displayScaleFactor).toFloat()
        )

        ensureUpdateIsCurrent(isStillValid)
        val reservations = reserveFramePixels(image.pagesLoaded, requiredSize)
        val resizedImage = try {
            resizeImage(image, dstWidth, dstHeight)
        } catch (e: Throwable) {
            reservations.forEach { it.close() }
            throw e
        }
        val frameOwnership = mutableListOf<TileOwnership>()
        val frameHandles = mutableListOf<TileHandle>()
        val frames = mutableListOf<ImageFrame>()
        var ownedFrameCount = 0
        var rawFramesAlreadyClosed = false
        var succeeded = false
        try {
            stats.renderedTiles += 1
            try {
                requireSatisfiedSize(resizedImage, requiredSize)
            } catch (e: Throwable) {
                rawFramesAlreadyClosed = true
                throw e
            }

            ensureUpdateIsCurrent(isStillValid)
            if (generationFor(displayArea) != generation) return UpdateResult.STALE
            if (resizedImage.frames.size > reservations.size) {
                throw IllegalStateException(
                    "page ${pageId.pageNumber} returned ${resizedImage.frames.size} frames " +
                        "after reserving ${reservations.size}"
                )
            }

            resizedImage.frames.forEachIndexed { index, renderImage ->
                val owned = TileHandle.create(
                    renderImage = renderImage,
                    sourceBounds = sourceBounds,
                    displayRegion = tileDisplayRegion,
                    renderSize = IntSize(resizedImage.width, resizedImage.height),
                    satisfiedSize = resizedImage.satisfiedSize,
                    renderGeneration = generation,
                    closeImage = { closeRenderImage(it) },
                )
                ownedFrameCount = index + 1
                val admitted = pixelLedger.attach(reservations[index], owned)
                    ?: return UpdateResult.STALE
                frameOwnership += admitted.ownership
                frameHandles += admitted.handle
                frames += ImageFrame(
                    tiles = listOf(readerTileOf(admitted.handle)),
                    delay = resizedImage.delays?.getOrNull(index) ?: defaultFrameDelay,
                )
            }

            ensureUpdateIsCurrent(isStillValid)

            // the tiled cache cannot serve a full resize frame, so it is retired here
            stats.evictedTiles += releaseRetainedTilesExcept(emptySet())

            ensureUpdateIsCurrent(isStillValid)
            frameData.value = FrameData(
                frames = frames,
                displaySize = displayArea,
                scaleFactor = scaleFactor,
                readiness = readiness,
                handles = frameHandles.toList(),
                frameOwnership = frameOwnership.toList(),
                retiredOwnership = frameData.value?.takePendingOwnership() ?: emptyList(),
            )
            lastUsedScaleFactor = scaleFactor
            succeeded = true
            logger.info { "page ${pageId.pageNumber} completed full resize to $dstWidth x $dstHeight" }
            return UpdateResult.PUBLISHED
        } finally {
            reservations.forEach { it.close() }
            if (!succeeded) {
                frameOwnership.forEach { it.close() }
                if (!rawFramesAlreadyClosed) {
                    resizedImage.frames.drop(ownedFrameCount).forEach { closeRenderImage(it) }
                }
            }
        }
    }

    // TODO support animations
    // does not handle animated images and assumes that there's only one frame
    private suspend fun doTile(
        image: KomeliaImage,
        requirements: List<ViewportRenderRequirement>,
        displayScaleFactor: Double,
        scaleFactor: Double,
        displayArea: IntSize,
        tileSize: Int,
        generation: Long,
        readiness: CompletableDeferred<Unit>?,
        stats: RenderPassStats,
        isStillValid: () -> Boolean,
    ): UpdateResult {
        val timeSource = TimeSource.Monotonic
        val start = timeSource.markNow()

        val passTiles = mutableListOf<PassTile>()
        var addedNewTiles = false
        var succeeded = false
        try {
            for (geometry in tileGeometries(image, tileSize, displayScaleFactor)) {
                val tileRegion = geometry.sourceBounds
                val tileDisplayRegion = geometry.displayRegion

                val requiredSize = requiredTileRenderSize(
                    tileSourceBounds = tileRegion,
                    tileDisplayRegion = tileDisplayRegion,
                    displayScaleFactor = displayScaleFactor,
                    displayArea = displayArea,
                    requirements = requirements,
                ) ?: continue

                val key = TileCacheKey(tileRegion, tileDisplayRegion, generation)
                val retained = retainedTiles.value[key]
                if (retained != null && retained.handle.isSufficientFor(requiredSize)) {
                    val frameOwnership = pixelLedger.retain(retained.handle)
                    if (frameOwnership != null) {
                        stats.reusedTiles += 1
                        passTiles += PassTile(
                            key = key,
                            handle = retained.handle,
                            frameOwnership = frameOwnership,
                            insertedOwnership = null,
                            tile = readerTileOf(retained.handle),
                        )
                        continue
                    }
                    if (!retained.handle.isClosed) {
                        throw TilePixelBudgetExceededException(
                            "page ${pageId.pageNumber} rolling window exceeds " +
                                "$hardPeakDisplayAreas display areas"
                        )
                    }
                    // the native image died under us; drop the record and render again
                    dropRetainedTile(key)
                }
                // an entry that survived the check above was retained but under-resolved
                val upgradesRetainedTile = retained != null &&
                    !retained.handle.isSufficientFor(requiredSize)

                ensureUpdateIsCurrent(isStillValid)
                val reservation = pixelLedger.reserve(requiredSize)
                    ?: throw TilePixelBudgetExceededException(
                        "page ${pageId.pageNumber} rolling window exceeds " +
                            "$hardPeakDisplayAreas display areas"
                    )
                val rendered = try {
                    getImageRegion(image, tileRegion, requiredSize.width, requiredSize.height)
                } catch (e: Throwable) {
                    reservation.close()
                    throw e
                }
                try {
                    stats.renderedTiles += 1
                    if (upgradesRetainedTile) stats.upgradedTiles += 1
                    requireSatisfiedSize(rendered, requiredSize)

                    if (!isStillValid()) {
                        rendered.frames.forEach { closeRenderImage(it) }
                        throw CancellationException("image update is obsolete")
                    }
                    if (generationFor(displayArea) != generation) {
                        rendered.frames.forEach { closeRenderImage(it) }
                        return UpdateResult.STALE
                    }

                    // the tiled path draws a single frame; any extra frame is ours to close
                    rendered.frames.drop(1).forEach { closeRenderImage(it) }
                    val owned = TileHandle.create(
                        renderImage = rendered.frames.first(),
                        sourceBounds = tileRegion,
                        displayRegion = tileDisplayRegion,
                        renderSize = IntSize(rendered.width, rendered.height),
                        satisfiedSize = rendered.satisfiedSize,
                        renderGeneration = generation,
                        closeImage = { closeRenderImage(it) },
                    )
                    val admitted = pixelLedger.attach(reservation, owned) ?: return UpdateResult.STALE
                    val frameOwnership = pixelLedger.retain(admitted.handle)
                    if (frameOwnership == null) {
                        admitted.ownership.close()
                        throw IllegalStateException(
                            "page ${pageId.pageNumber} could not take ownership of a rendered tile"
                        )
                    }

                    putRetainedTile(key, admitted.handle, admitted.ownership)
                    passTiles += PassTile(
                        key = key,
                        handle = admitted.handle,
                        frameOwnership = frameOwnership,
                        insertedOwnership = admitted.ownership,
                        tile = readerTileOf(admitted.handle),
                    )
                    addedNewTiles = true
                } finally {
                    reservation.close()
                }
            }

            val activeKeys = passTiles.mapTo(mutableSetOf()) { it.key }
            val newTiles = passTiles.map { it.tile }
            val previousFrame = frameData.value
            val previousTileRegions = previousFrame?.frames?.firstOrNull()?.tiles
                ?.mapTo(mutableSetOf()) { it.displayRegion }
            val coverageChanged = previousTileRegions != newTiles.mapTo(mutableSetOf()) { it.displayRegion }
            val scaleChanged = previousFrame?.scaleFactor != scaleFactor
            val shouldPublish = newTiles.isNotEmpty() && (addedNewTiles || coverageChanged || scaleChanged)

            ensureUpdateIsCurrent(isStillValid)
            if (shouldPublish) {
                frameData.value = FrameData(
                    frames = listOf(ImageFrame(newTiles, 0)),
                    displaySize = displayArea,
                    scaleFactor = scaleFactor,
                    readiness = readiness,
                    handles = passTiles.map { it.handle },
                    frameOwnership = passTiles.map { it.frameOwnership },
                    retiredOwnership = previousFrame?.takePendingOwnership() ?: emptyList(),
                )
                val end = timeSource.markNow()
                logger.info {
                    "page ${pageId.pageNumber} completed tiled resize in ${end - start};  ${newTiles.size} tiles"
                }
            } else {
                passTiles.forEach { it.frameOwnership.close() }
            }

            retainDemandTiles(activeKeys)
            lastUsedScaleFactor = scaleFactor
            succeeded = true
            return if (shouldPublish) UpdateResult.PUBLISHED else UpdateResult.UNCHANGED
        } finally {
            if (!succeeded) {
                // a failed or stale pass owns nothing: the visible frame and the rest of the
                // retained working set are untouched
                for (pass in passTiles) {
                    pass.frameOwnership.close()
                    pass.insertedOwnership?.let { inserted ->
                        dropRetainedTile(pass.key)
                        inserted.close()
                    }
                }
            }
        }
    }

    private fun tileSizeFor(displayPixCount: Int): Int? = when (displayPixCount) {
        in 0..tileThreshold1 -> null
        in tileThreshold1..tileThreshold2 -> 1024
        in tileThreshold2..tileThreshold3 -> 512
        else -> 256
    }

    private fun hardPeakPixelsFor(displaySize: IntSize): Long {
        val displayPixels = checkedPixelCount(displaySize)
        val displayAreas = hardPeakDisplayAreas.toLong()
        require(displayAreas >= 0) { "hard peak display areas must not be negative" }
        check(displayAreas == 0L || displayPixels <= Long.MAX_VALUE / displayAreas)
        return displayPixels * displayAreas
    }

    private suspend fun reserveFramePixels(
        frameCount: Int,
        size: IntSize,
    ): List<PixelReservation> {
        require(frameCount > 0) { "a rendered image must contain at least one frame" }
        val reservations = mutableListOf<PixelReservation>()
        try {
            repeat(frameCount) {
                reservations += pixelLedger.reserve(size)
                    ?: throw TilePixelBudgetExceededException(
                        "page ${pageId.pageNumber} render exceeds $hardPeakDisplayAreas display areas"
                    )
            }
            return reservations
        } catch (e: Throwable) {
            reservations.forEach { it.close() }
            throw e
        }
    }

    private fun ensureUpdateIsCurrent(isStillValid: () -> Boolean) {
        if (!isStillValid()) throw CancellationException("image update is obsolete")
    }

    private fun requireSatisfiedSize(rendered: ReaderImageData, requiredSize: IntSize) {
        if (
            rendered.satisfiedSize.width >= requiredSize.width &&
            rendered.satisfiedSize.height >= requiredSize.height
        ) {
            return
        }

        rendered.frames.forEach { closeRenderImage(it) }
        throw IllegalStateException(
            "page ${pageId.pageNumber} backend returned ${rendered.width} x ${rendered.height} " +
                "satisfying ${rendered.satisfiedSize.width} x ${rendered.satisfiedSize.height} " +
                "for requested ${requiredSize.width} x ${requiredSize.height}"
        )
    }

    /** Source tile grid and its fitted display rectangles. */
    private fun tileGeometries(
        image: KomeliaImage,
        tileSize: Int,
        displayScaleFactor: Double,
    ): List<TileGeometry> {
        val geometries = mutableListOf<TileGeometry>()
        var yTaken = 0
        while (yTaken != image.height) {
            var xTaken = 0
            while (xTaken != image.width) {
                val tileRegion = IntRect(
                    top = yTaken.coerceAtMost(image.height),
                    bottom = (yTaken + tileSize).coerceAtMost(image.height),
                    left = (xTaken).coerceAtMost(image.width),
                    right = (xTaken + tileSize).coerceAtMost(image.width),
                )
                geometries += TileGeometry(
                    sourceBounds = tileRegion,
                    displayRegion = Rect(
                        (tileRegion.left * displayScaleFactor).toFloat(),
                        (tileRegion.top * displayScaleFactor).toFloat(),
                        (tileRegion.right * displayScaleFactor).toFloat(),
                        (tileRegion.bottom * displayScaleFactor).toFloat()
                    ),
                )
                xTaken = (xTaken + tileSize).coerceAtMost(image.width)
            }
            yTaken = (yTaken + tileSize).coerceAtMost(image.height)
        }
        return geometries
    }

    private fun readerTileOf(handle: TileHandle) = ReaderImageTile(
        size = handle.renderSize,
        displayRegion = handle.displayRegion,
        isVisible = true,
        renderImage = handle.renderImage,
    )

    private fun generationFor(displaySize: IntSize): Long {
        val fingerprint = RenderFingerprint(
            pageId = pageId,
            imageEpoch = imageEpoch,
            displaySize = displaySize,
            stretchImages = stretchImages.value,
            downSamplingKernel = downSamplingKernel.value,
            linearLightDownSampling = linearLightDownSampling.value,
            upsamplingMode = upsamplingMode.value,
            rendererEpoch = rendererEpoch,
        )
        if (fingerprint != renderFingerprint) {
            val onlyDisplaySizeChanged =
                renderFingerprint?.copy(displaySize = fingerprint.displaySize) == fingerprint
            renderFingerprint = fingerprint
            renderGeneration += 1
            lastUsedScaleFactor = null
            // the page frame survives layout jitter, but not a real change of geometry or of any
            // raster input it was rendered with. A surviving frame is re-stamped to the new
            // generation, so hasRetainedPageFrame and publishPageFrame stay truthful instead of
            // having to special-case an older generation.
            // frame is read after the bump; its displaySize is the pre-drift size, the drift check's reference
            val frame = retainedFitBase.value
            val keepPageFrame = onlyDisplaySizeChanged &&
                frame != null &&
                !isMaterialDisplayChange(frame.displaySize, fingerprint.displaySize)
            if (keepPageFrame) {
                retainedFitBase.update { it?.retagged(renderGeneration) }
            } else {
                releaseRetainedFitBase()
            }
        }
        return renderGeneration
    }

    /**
     * Whether the fitted display size has moved far enough from the one the retained page frame
     * was rendered at that the frame must be discarded. The frame is only ever drawn for the
     * 250 ms of a panel transition, and its tiles carry absolute display coordinates, so a few
     * pixels of staleness is invisible. The drift is measured against the frame's own size, not
     * the previous fingerprint's, so successive tolerated jitters cannot add up past the bound.
     */
    private fun isMaterialDisplayChange(previous: IntSize, next: IntSize): Boolean {
        val widthDrift = abs(previous.width - next.width)
        val heightDrift = abs(previous.height - next.height)
        return widthDrift > maxToleratedDisplayDrift || heightDrift > maxToleratedDisplayDrift
    }

    /**
     * Records a change of a producing input owned by a platform renderer, for example the
     * active upscaler model. Must be called from the processing scope before the reload.
     */
    protected fun invalidateRenderGeneration() {
        rendererEpoch += 1
    }

    internal suspend fun ensureFitPageBase(maxDisplaySize: IntSize): RetainedFitPageBase {
        var result: RetainedFitPageBase? = null
        arbiter.submitDemand {
            result = ensureFitPageBaseOnLane(maxDisplaySize)
        }
        return checkNotNull(result)
    }

    private suspend fun ensureFitPageBaseOnLane(maxDisplaySize: IntSize): RetainedFitPageBase {
        require(maxDisplaySize.width > 0 && maxDisplaySize.height > 0) {
            "fit page base needs a positive display area"
        }
        val currentImage = getCurrentImage()
        val fittedSize = checkNotNull(calculateSizeForArea(maxDisplaySize, stretchImages.value)) {
            "page ${pageId.pageNumber} has no fitted display size"
        }
        require(fittedSize.width > 0 && fittedSize.height > 0) {
            "fit page base needs positive fitted dimensions"
        }

        val generation = generationFor(fittedSize)
        pixelLedger.setHardPeakPixels(hardPeakPixelsFor(fittedSize))
        retainedFitBase.value?.takeIf {
            it.generation == generation && !isMaterialDisplayChange(it.displaySize, fittedSize)
        }?.let { return it }

        val reservations = reserveFramePixels(currentImage.pagesLoaded, fittedSize)
        val resized = try {
            resizeImage(currentImage, fittedSize.width, fittedSize.height)
        } catch (e: Throwable) {
            reservations.forEach { it.close() }
            throw e
        }

        val sourceBounds = IntRect(0, 0, currentImage.width, currentImage.pageHeight)
        val displayRegion = Rect(0f, 0f, fittedSize.width.toFloat(), fittedSize.height.toFloat())
        val handles = mutableListOf<TileHandle>()
        val ownership = mutableListOf<TileOwnership>()
        val frames = mutableListOf<ImageFrame>()
        var ownedFrameCount = 0
        var rawFramesAlreadyClosed = false
        var succeeded = false
        try {
            try {
                requireSatisfiedSize(resized, fittedSize)
            } catch (e: Throwable) {
                rawFramesAlreadyClosed = true
                throw e
            }
            if (resized.width != fittedSize.width || resized.height != fittedSize.height) {
                throw IllegalStateException(
                    "page ${pageId.pageNumber} fit base returned ${resized.width} x ${resized.height} " +
                        "for fitted ${fittedSize.width} x ${fittedSize.height}"
                )
            }
            if (resized.frames.isEmpty()) {
                throw IllegalStateException("page ${pageId.pageNumber} fit base returned no frames")
            }
            if (resized.frames.size > reservations.size) {
                throw IllegalStateException(
                    "page ${pageId.pageNumber} fit base returned ${resized.frames.size} frames " +
                        "after reserving ${reservations.size}"
                )
            }
            if (generationFor(fittedSize) != generation) {
                resized.frames.forEach { closeRenderImage(it) }
                rawFramesAlreadyClosed = true
                reservations.forEach { it.close() }
                return ensureFitPageBaseOnLane(maxDisplaySize)
            }

            resized.frames.forEachIndexed { index, renderImage ->
                val owned = TileHandle.create(
                    renderImage = renderImage,
                    sourceBounds = sourceBounds,
                    displayRegion = displayRegion,
                    renderSize = fittedSize,
                    satisfiedSize = resized.satisfiedSize,
                    renderGeneration = generation,
                    closeImage = { closeRenderImage(it) },
                )
                ownedFrameCount = index + 1
                val admitted = pixelLedger.attach(reservations[index], owned)
                    ?: throw TilePixelBudgetExceededException(
                        "page ${pageId.pageNumber} fit base exceeded its pixel reservation"
                    )
                handles += admitted.handle
                ownership += admitted.ownership
                frames += ImageFrame(
                    tiles = listOf(readerTileOf(admitted.handle)),
                    delay = resized.delays?.getOrNull(index) ?: defaultFrameDelay,
                )
            }

            val retained = RetainedFitPageBase(
                generation = generation,
                displaySize = fittedSize,
                handles = handles.toList(),
                frames = frames.toList(),
                ownership = ownership.toList(),
            )
            retainedFitBase.getAndUpdate { retained }?.release()
            succeeded = true
            return retained
        } finally {
            reservations.forEach { it.close() }
            if (!succeeded) {
                ownership.forEach { it.close() }
                if (!rawFramesAlreadyClosed) {
                    resized.frames.drop(ownedFrameCount).forEach { closeRenderImage(it) }
                }
            }
        }
    }

    private fun fitScaleFactor(displaySize: IntSize, image: KomeliaImage): Double =
        (displaySize.width.toDouble() / image.width)
            .coerceAtMost(displaySize.height.toDouble() / image.pageHeight)

    /** Releases the retained fit base if one is held. Idempotent under concurrent callers. */
    private fun releaseRetainedFitBase() {
        retainedFitBase.getAndUpdate { null }?.release()
    }

    private fun putRetainedTile(
        key: TileCacheKey,
        handle: TileHandle,
        ownership: TileOwnership,
    ) {
        val tile = RetainedTile(
            handle = handle,
            ownership = ownership,
        )
        val previous = retainedTiles.getAndUpdate { it + (key to tile) }[key]
        previous?.ownership?.close()
    }

    /** Keeps only the coverage this pass published in the reusable working set. */
    private fun retainDemandTiles(activeKeys: Set<TileCacheKey>) {
        val retired = mutableListOf<TileOwnership>()
        retainedTiles.update { current ->
            retired.clear()
            val next = LinkedHashMap<TileCacheKey, RetainedTile>(current.size)
            for ((key, tile) in current) {
                if (key in activeKeys) next[key] = tile
                else retired += tile.ownership
            }
            next
        }
        retired.forEach { it.close() }
    }

    private fun dropRetainedTile(key: TileCacheKey) {
        retainedTiles.getAndUpdate { it - key }[key]?.ownership?.close()
    }

    /** @return how many working-set entries were released */
    private fun releaseRetainedTilesExcept(activeKeys: Set<TileCacheKey>): Int {
        val previous = retainedTiles.getAndUpdate { current -> current.filterKeys { it in activeKeys } }
        val released = previous.filterKeys { it !in activeKeys }.values
        released.forEach { it.ownership.close() }
        return released.size
    }

    override fun close() {
        // draining first unblocks callers awaiting queued work that the cancelled lane will
        // never run
        arbiter.close()
        processingScope.cancel()
        animationScope.cancel()
        imageAwaitScope.cancel()

        val data = frameData.value
        frameData.value = null
        originalImage?.close()
        data?.releaseAll()
        releaseRetainedFitBase()
        releaseRetainedTilesExcept(emptySet())
        image.value?.close()
        imageSource.close()
    }

    /** Releases the native image behind a tile. Called exactly once per image. */
    protected abstract fun closeRenderImage(renderImage: RenderImage)

    protected abstract fun createTilePainter(
        tiles: List<ReaderImageTile>,
        displaySize: IntSize,
        scaleFactor: Double,
    ): TiledPainter

    protected abstract suspend fun resizeImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData

    protected abstract suspend fun getImageRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData

    data class ReaderImageTile(
        val size: IntSize,
        val displayRegion: Rect,
        val isVisible: Boolean,
        val renderImage: RenderImage?,
    )

    data class ReaderImageData(
        val width: Int,
        val height: Int,
        val satisfiedSize: IntSize,
        val frames: List<RenderImage>,
        val delays: List<Long>?,
    )

    abstract class TiledPainter() : Painter() {
        abstract fun withSamplingMode(upsamplingMode: UpsamplingMode): TiledPainter
    }

    internal class FrameData(
        val frames: List<ImageFrame>,
        val displaySize: IntSize,
        val scaleFactor: Double,
        val readiness: CompletableDeferred<Unit>?,
        private val handles: List<TileHandle>,
        frameOwnership: List<TileOwnership>,
        retiredOwnership: List<TileOwnership>,
    ) {
        private val ownership = MutableStateFlow(frameOwnership)
        private val retired = MutableStateFlow(retiredOwnership)

        /**
         * Takes an independent reference to every handle that satisfied readiness, so a held
         * preparation lease keeps its coverage alive regardless of what the frame does next.
         */
        suspend fun retainHandles(ledger: TilePixelLedger): List<TileOwnership> =
            handles.mapNotNull { ledger.retain(it) }

        /** Releases the frame this one replaced, once this frame's painter is actually visible. */
        fun releaseRetired() {
            retired.getAndUpdate { emptyList() }.forEach { it.close() }
        }

        /**
         * Hands every reference this frame still owns to its replacement. A frame that is
         * superseded before it ever publishes therefore cannot strand its tiles.
         */
        fun takePendingOwnership(): List<TileOwnership> =
            ownership.getAndUpdate { emptyList() } + retired.getAndUpdate { emptyList() }

        fun releaseAll() {
            takePendingOwnership().forEach { it.close() }
        }
    }

    data class ImageFrame(
        val tiles: List<ReaderImageTile>,
        val delay: Long
    )

    private enum class UpdateResult {
        /** a new painter was handed to the frame pipeline */
        PUBLISHED,

        /** the installed painter already satisfies the request */
        UNCHANGED,

        /** a producing input changed mid pass, so the pass output was released unpublished */
        STALE,
    }

    internal data class TileCacheKey(
        val sourceBounds: IntRect,
        val displayRegion: Rect,
        val generation: Long,
    )

    internal class RetainedTile(
        val handle: TileHandle,
        val ownership: TileOwnership,
    )

    private data class TileGeometry(
        val sourceBounds: IntRect,
        val displayRegion: Rect,
    )

    private class PassTile(
        val key: TileCacheKey,
        val handle: TileHandle,
        val frameOwnership: TileOwnership,
        val insertedOwnership: TileOwnership?,
        val tile: ReaderImageTile,
    )

    private data class RenderFingerprint(
        val pageId: ReaderImage.PageId,
        val imageEpoch: Long,
        val displaySize: IntSize,
        val stretchImages: Boolean,
        val downSamplingKernel: ReduceKernel,
        val linearLightDownSampling: Boolean,
        val upsamplingMode: UpsamplingMode,
        val rendererEpoch: Long,
    )
}
