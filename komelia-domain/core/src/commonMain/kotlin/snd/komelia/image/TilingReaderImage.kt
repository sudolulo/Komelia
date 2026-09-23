package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import io.github.oshai.kotlinlogging.KotlinLogging
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.selects.select
import snd.komelia.image.processing.ImageProcessingPipeline
import kotlin.concurrent.Volatile
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.time.TimeSource

private const val tileThreshold1 = 2048 * 2048
private const val tileThreshold2 = 4096 * 4096
private const val tileThreshold3 = 6144 * 6144

/**
 * The hard peak is expressed in fitted display areas; see the panel transition design doc.
 */
private const val hardPeakDisplayAreas = 8L

/**
 * A render input change invalidates an in-flight pass and the pass restarts. Restarting forever
 * would hide a thrashing settings flow, so a bounded number of restarts is allowed per request.
 */
private const val maxGenerationRetries = 8

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
    final override val pageId: ReaderImage.PageId
) : ReaderImage {
    final override val painter = MutableStateFlow<TiledPainter?>(null)
    final override val error = MutableStateFlow<Throwable?>(null)

    final override val originalSize = MutableStateFlow<IntSize?>(null)
    final override val displaySize = MutableStateFlow<IntSize?>(null)
    final override val currentSize = MutableStateFlow<IntSize?>(null)

    private val imageAwaitScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val animationScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())
    protected val processingScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1) + SupervisorJob())

    private val jobFlow = MutableSharedFlow<UpdateRequest>(1, 0, BufferOverflow.DROP_OLDEST)
    private val frameData = MutableStateFlow<FrameData?>(null)
    private val updateMutex = Mutex()
    protected val image = MutableStateFlow<KomeliaImage?>(null)
    protected val defaultFrameDelay = 100L

    private val retainedTiles = MutableStateFlow<Map<TileCacheKey, RetainedTile>>(emptyMap())

    // all of the fields below are mutated only from the single threaded processing scope
    private var pixelBudget: TilePixelBudget? = null
    private var pixelBudgetPeak = 0L
    private var renderGeneration = 0L
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
                processUpdate(request)
                delay(50)
            }.launchIn(processingScope)

        processingPipeline.changeFlow.onEach {
            val currentImage = image.value
            if (currentImage !== originalImage) {
                currentImage?.close()
            }

            imageEpoch += 1
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

    private suspend fun processUpdate(request: UpdateRequest) {
        try {
            updateMutex.withLock {
                processUpdateLocked(request)
            }
            error.value = null
        } catch (e: Throwable) {
            currentCoroutineContext().ensureActive()
            logger.catching(e)
            error.value = e
            request.readiness?.completeExceptionally(e)
        }
    }

    private suspend fun processUpdateLocked(request: UpdateRequest) {
        val publishedFrame = doUpdate(request)
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
    ): PreparedUpdateLease {
        require(requirements.isNotEmpty()) { "prepareUpdate needs at least one viewport requirement" }
        val readiness = CompletableDeferred<Unit>()
        val lease = PreparedUpdateLeaseImpl()
        val prepared = CompletableDeferred<PreparedUpdateLease>()
        val owner = Any()
        val job = processingScope.launch {
            var gateAcquired = false
            var leaseOwnership: List<TileOwnership> = emptyList()
            try {
                updateMutex.lock(owner)
                gateAcquired = true
                val request = UpdateRequest(
                    requirements = requirements,
                    maxDisplaySize = maxDisplaySize,
                    readiness = readiness,
                )
                processUpdateLocked(request)
                error.value = null
                // the lease owns the exact handle set that satisfied readiness
                leaseOwnership = frameData.value?.retainHandles() ?: emptyList()
                prepared.complete(lease)
                lease.awaitRelease()
            } catch (e: Throwable) {
                currentCoroutineContext().ensureActive()
                logger.catching(e)
                error.value = e
                readiness.completeExceptionally(e)
                prepared.completeExceptionally(e)
            } finally {
                leaseOwnership.forEach { it.close() }
                if (gateAcquired) updateMutex.unlock(owner)
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

    private class PreparedUpdateLeaseImpl : PreparedUpdateLease {
        private val released = CompletableDeferred<Unit>()

        override fun close() {
            released.complete(Unit)
        }

        suspend fun awaitRelease() {
            released.await()
        }
    }

    private suspend fun doUpdate(request: UpdateRequest): Boolean {
        lastUpdateRequest = request.copy(readiness = null)

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

        this.displaySize.value = displaySize
        this.currentSize.value = IntSize(dstWidth.roundToInt(), dstHeight.roundToInt())

        val displayPixCount = (dstWidth * dstHeight).roundToInt()
        val tileSize = when (displayPixCount) {
            in 0..tileThreshold1 -> null
            in tileThreshold1..tileThreshold2 -> 1024
            in tileThreshold2..tileThreshold3 -> 512
            else -> 256
        }

        val budget = budgetFor(request.maxDisplaySize)

        repeat(maxGenerationRetries) {
            val generation = generationFor(displaySize)
            val result = if (image.pagesLoaded > 1 || tileSize == null) {
                doFullResize(
                    image = image,
                    scaleFactor = actualScaleFactor,
                    displayScaleFactor = displayScaleFactor,
                    displayArea = displaySize,
                    generation = generation,
                    budget = budget,
                    readiness = request.readiness,
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
                    budget = budget,
                    readiness = request.readiness,
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
        budget: TilePixelBudget,
        readiness: CompletableDeferred<Unit>?,
    ): UpdateResult {
        if (lastUsedScaleFactor == scaleFactor) {
            error.value?.let { throw (it) }
            return UpdateResult.UNCHANGED
        }

        val dstWidth = checkedRenderDimension(image.width, scaleFactor)
        val dstHeight = checkedRenderDimension(image.pageHeight, scaleFactor)
        val sourceBounds = IntRect(0, 0, image.width, image.pageHeight)
        val tileDisplayRegion = Rect(
            0f,
            0f,
            round(image.width * displayScaleFactor).toFloat(),
            round(image.pageHeight * displayScaleFactor).toFloat()
        )

        val reservation = budget.reserve(dstWidth, dstHeight)
            ?: throw TilePixelBudgetExceededException(
                "page ${pageId.pageNumber} cannot admit a ${dstWidth}x$dstHeight full resize"
            )

        val resizedImage = try {
            resizeImage(image, dstWidth, dstHeight)
        } catch (e: Throwable) {
            reservation.close()
            throw e
        }

        if (generationFor(displayArea) != generation) {
            reservation.close()
            resizedImage.frames.forEach { closeRenderImage(it) }
            return UpdateResult.STALE
        }

        val frameOwnership = mutableListOf<TileOwnership>()
        val frameHandles = mutableListOf<TileHandle>()
        val frames = mutableListOf<ImageFrame>()
        var succeeded = false
        try {
            resizedImage.frames.forEachIndexed { index, renderImage ->
                val owned = TileHandle.create(
                    renderImage = renderImage,
                    sourceBounds = sourceBounds,
                    displayRegion = tileDisplayRegion,
                    renderSize = IntSize(resizedImage.width, resizedImage.height),
                    renderGeneration = generation,
                    closeImage = { closeRenderImage(it) },
                )
                // only the first frame is covered by the reservation; the rest go through
                // ordinary admission so an animation can never blow past the hard peak
                val admitted = if (index == 0) budget.attach(reservation, owned) else owned
                if (admitted == null) {
                    throw IllegalStateException(
                        "page ${pageId.pageNumber} full resize exceeded its ${dstWidth}x$dstHeight reservation"
                    )
                }

                val ownership = budget.retain(admitted.handle)
                admitted.ownership.close()
                if (ownership == null) {
                    throw TilePixelBudgetExceededException(
                        "page ${pageId.pageNumber} cannot admit full resize frame $index"
                    )
                }

                frameOwnership += ownership
                frameHandles += admitted.handle
                frames += ImageFrame(
                    tiles = listOf(readerTileOf(admitted.handle)),
                    delay = resizedImage.delays?.getOrNull(index) ?: defaultFrameDelay,
                )
            }

            // the tiled cache cannot serve a full resize frame, so it is retired here
            releaseRetainedTilesExcept(emptySet())

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
            if (!succeeded) {
                reservation.close()
                frameOwnership.forEach { it.close() }
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
        budget: TilePixelBudget,
        readiness: CompletableDeferred<Unit>?,
    ): UpdateResult {
        val timeSource = TimeSource.Monotonic
        val start = timeSource.markNow()

        val passTiles = mutableListOf<PassTile>()
        var addedNewTiles = false
        var succeeded = false
        try {
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
                    val tileDisplayRegion = Rect(
                        (tileRegion.left * displayScaleFactor).toFloat(),
                        (tileRegion.top * displayScaleFactor).toFloat(),
                        (tileRegion.right * displayScaleFactor).toFloat(),
                        (tileRegion.bottom * displayScaleFactor).toFloat()
                    )
                    xTaken = (xTaken + tileSize).coerceAtMost(image.width)

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
                        val frameOwnership = budget.retain(retained.handle)
                        if (frameOwnership != null) {
                            passTiles += PassTile(
                                key = key,
                                handle = retained.handle,
                                frameOwnership = frameOwnership,
                                insertedOwnership = null,
                                tile = readerTileOf(retained.handle),
                            )
                            continue
                        }
                        // the native image died under us; drop the record and render again
                        dropRetainedTile(key)
                    }

                    val reservation = budget.reserve(requiredSize.width, requiredSize.height)
                        ?: throw TilePixelBudgetExceededException(
                            "page ${pageId.pageNumber} cannot admit a " +
                                "${requiredSize.width}x${requiredSize.height} tile at $tileRegion"
                        )

                    val rendered = try {
                        getImageRegion(image, tileRegion, requiredSize.width, requiredSize.height)
                    } catch (e: Throwable) {
                        reservation.close()
                        throw e
                    }

                    if (generationFor(displayArea) != generation) {
                        reservation.close()
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
                        renderGeneration = generation,
                        closeImage = { closeRenderImage(it) },
                    )
                    val admitted = budget.attach(reservation, owned)
                        ?: throw IllegalStateException(
                            "page ${pageId.pageNumber} tile render exceeded its " +
                                "${requiredSize.width}x${requiredSize.height} reservation"
                        )

                    val insertedOwnership = budget.retain(admitted.handle)
                    val frameOwnership = insertedOwnership?.let { budget.retain(admitted.handle) }
                    admitted.ownership.close()
                    if (insertedOwnership == null || frameOwnership == null) {
                        insertedOwnership?.close()
                        throw IllegalStateException(
                            "page ${pageId.pageNumber} could not take ownership of a rendered tile"
                        )
                    }

                    putRetainedTile(key, RetainedTile(admitted.handle, insertedOwnership))
                    passTiles += PassTile(
                        key = key,
                        handle = admitted.handle,
                        frameOwnership = frameOwnership,
                        insertedOwnership = insertedOwnership,
                        tile = readerTileOf(admitted.handle),
                    )
                    addedNewTiles = true
                }
                yTaken = (yTaken + tileSize).coerceAtMost(image.height)
            }

            val activeKeys = passTiles.mapTo(mutableSetOf()) { it.key }
            val newTiles = passTiles.map { it.tile }
            val previousFrame = frameData.value
            val previousTileRegions = previousFrame?.frames?.firstOrNull()?.tiles
                ?.mapTo(mutableSetOf()) { it.displayRegion }
            val coverageChanged = previousTileRegions != newTiles.mapTo(mutableSetOf()) { it.displayRegion }
            val scaleChanged = previousFrame?.scaleFactor != scaleFactor
            val shouldPublish = newTiles.isNotEmpty() && (addedNewTiles || coverageChanged || scaleChanged)

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

            releaseRetainedTilesExcept(activeKeys)
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

    private fun readerTileOf(handle: TileHandle) = ReaderImageTile(
        size = handle.renderSize,
        displayRegion = handle.displayRegion,
        isVisible = true,
        renderImage = handle.renderImage,
    )

    private fun budgetFor(maxDisplaySize: IntSize): TilePixelBudget {
        val peak = hardPeakDisplayAreas * checkedPixelCount(maxDisplaySize)
        val existing = pixelBudget
        if (existing != null && peak == pixelBudgetPeak) return existing

        // a different display area means different fitted geometry, so nothing retained
        // under the previous budget can be reused anyway
        releaseRetainedTilesExcept(emptySet())
        val created = TilePixelBudget(peak)
        pixelBudget = created
        pixelBudgetPeak = peak
        return created
    }

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
            renderFingerprint = fingerprint
            renderGeneration += 1
            lastUsedScaleFactor = null
        }
        return renderGeneration
    }

    /**
     * Records a change of a producing input owned by a platform renderer, for example the
     * active upscaler model. Must be called from the processing scope before the reload.
     */
    protected fun invalidateRenderGeneration() {
        rendererEpoch += 1
    }

    private fun putRetainedTile(key: TileCacheKey, tile: RetainedTile) {
        val previous = retainedTiles.getAndUpdate { it + (key to tile) }[key]
        previous?.ownership?.close()
    }

    private fun dropRetainedTile(key: TileCacheKey) {
        retainedTiles.getAndUpdate { it - key }[key]?.ownership?.close()
    }

    private fun releaseRetainedTilesExcept(activeKeys: Set<TileCacheKey>) {
        val previous = retainedTiles.getAndUpdate { current -> current.filterKeys { it in activeKeys } }
        previous.filterKeys { it !in activeKeys }.values.forEach { it.ownership.close() }
    }

    override fun close() {
        processingScope.cancel()
        animationScope.cancel()
        imageAwaitScope.cancel()

        val data = frameData.value
        frameData.value = null
        originalImage?.close()
        data?.releaseAll()
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
        fun retainHandles(): List<TileOwnership> = handles.mapNotNull { it.retain() }

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

    private data class TileCacheKey(
        val sourceBounds: IntRect,
        val displayRegion: Rect,
        val generation: Long,
    )

    private class RetainedTile(
        val handle: TileHandle,
        val ownership: TileOwnership,
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
