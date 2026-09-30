package snd.komelia.image

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.graphics.toSkiaRect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jetbrains.skia.Image
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.SkiaBitmap.toSkiaBitmap
import snd.komelia.image.processing.ImageProcessingPipeline

@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
actual typealias RenderImage = Image

class DesktopReaderImage(
    imageDecoder: KomeliaImageDecoder,
    imageSource: ImageSource,
    processingPipeline: ImageProcessingPipeline,
    stretchImages: StateFlow<Boolean>,
    pageId: PageId,
    upsamplingMode: StateFlow<UpsamplingMode>,
    downSamplingKernel: StateFlow<ReduceKernel>,
    linearLightDownSampling: StateFlow<Boolean>,
    private val upscaler: KomeliaUpscaler?,
    private val showDebugGrid: StateFlow<Boolean>,
) : TilingReaderImage(
    imageDecoder = imageDecoder,
    imageSource = imageSource,
    processingPipeline = processingPipeline,
    stretchImages = stretchImages,
    upsamplingMode = upsamplingMode,
    downSamplingKernel = downSamplingKernel,
    linearLightDownSampling = linearLightDownSampling,
    pageId = pageId,
) {

    init {
        upscaler?.upscaleMode?.drop(1)?.onEach {
            invalidateRenderGeneration()
            invalidatePanelGeometry()
            reloadLastRequest()
        }?.launchIn(processingScope)
    }

    override fun closeRenderImage(renderImage: RenderImage) {
        runCatching { renderImage.close() }
    }

    override fun createTilePainter(
        tiles: List<ReaderImageTile>,
        displaySize: IntSize,
        scaleFactor: Double
    ): TiledPainter {
        return SkiaTiledPainter(
            tiles = tiles,
            showDebugGrid = showDebugGrid.value,
            upsamplingMode = upsamplingMode.value,
            scaleFactor = scaleFactor,
            displaySize = displaySize
        )
    }

    override suspend fun resizeImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData {
        if (scaleWidth > image.width || scaleHeight > image.pageHeight) {
            return upscaleImage(image, scaleWidth, scaleHeight)
        }

        val downscaled = image.resize(
            scaleWidth = scaleWidth,
            scaleHeight = scaleHeight,
            linear = linearLightDownSampling.value,
            kernel = downSamplingKernel.value
        )
        val requestedSize = IntSize(scaleWidth, scaleHeight)
        requireCompleteSize(downscaled, requestedSize)
        val imageData = downscaled.toReaderImageData(requestedSize)
        downscaled.close()
        return imageData
    }

    override suspend fun getImageRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData {
        var region: KomeliaImage? = null
        var resized: KomeliaImage? = null
        try {
            region = image.extractArea(imageRegion.toImageRect())
            if (scaleWidth > imageRegion.width || scaleHeight > imageRegion.height) {
                return upscaleRegion(image, imageRegion, scaleWidth, scaleHeight)
            }
            resized = region.resize(
                scaleWidth = scaleWidth,
                scaleHeight = scaleHeight,
                linear = linearLightDownSampling.value,
                kernel = downSamplingKernel.value
            )
            val requestedSize = IntSize(scaleWidth, scaleHeight)
            requireCompleteSize(resized, requestedSize)
            return resized.toReaderImageData(requestedSize)
        } finally {
            region?.close()
            resized?.close()
        }
    }

    private suspend fun upscaleImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        val upscaled = upscaler?.upscale(image, pageId.toString())

        if (upscaled != null) {
            requireCompleteUpscale(image, upscaled)
            if (upscaled.width > scaleWidth && upscaled.pageHeight > scaleHeight) {
                val resized = upscaled.resize(
                    scaleWidth = scaleWidth,
                    scaleHeight = scaleHeight,
                    linear = linearLightDownSampling.value,
                    kernel = downSamplingKernel.value
                )
                val requestedSize = IntSize(scaleWidth, scaleHeight)
                requireCompleteSize(resized, requestedSize)
                val imageData = resized.toReaderImageData(requestedSize)
                resized.close()
                return imageData
            } else {
                val actualSize = IntSize(upscaled.width, upscaled.pageHeight)
                val imageData = upscaled.toReaderImageData(
                    completeResultSatisfiedSize(actualSize, IntSize(scaleWidth, scaleHeight))
                )
                return imageData
            }
        } else {
            val actualSize = IntSize(image.width, image.pageHeight)
            val imageData = image.toReaderImageData(
                completeResultSatisfiedSize(actualSize, IntSize(scaleWidth, scaleHeight))
            )
            return imageData
        }
    }


    private suspend fun upscaleRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int
    ): ReaderImageData {
        // try to reuse full resized image instead of upscaling individual regions
        val upscaled = upscaler?.upscale(image, pageId.toString())
        var region: KomeliaImage? = null
        var resized: KomeliaImage? = null

        try {
            if (upscaled != null) {
                // assume upscaling is done by integer fraction (2x, 4x etc.)
                val scaleRatio = requireCompleteUpscale(image, upscaled)
                val targetRegion = ImageRect(
                    left = imageRegion.left * scaleRatio,
                    right = imageRegion.right * scaleRatio,
                    top = imageRegion.top * scaleRatio,
                    bottom = imageRegion.bottom * scaleRatio
                )
                region = upscaled.extractArea(targetRegion)
                val completeRegionSize = IntSize(targetRegion.width, targetRegion.height)
                requireCompleteSize(region, completeRegionSize)

                // downscale if region is bigger than requested scale
                if (region.width > scaleWidth || region.pageHeight > scaleHeight) {
                    resized = region.resize(
                        scaleWidth = scaleWidth,
                        scaleHeight = scaleHeight,
                        linear = linearLightDownSampling.value,
                        kernel = downSamplingKernel.value
                    )
                    val requestedSize = IntSize(scaleWidth, scaleHeight)
                    requireCompleteSize(resized, requestedSize)
                    return resized.toReaderImageData(requestedSize)
                    // otherwise do not upsample and return original region size
                } else {
                    return region.toReaderImageData(
                        completeResultSatisfiedSize(completeRegionSize, IntSize(scaleWidth, scaleHeight))
                    )
                }

                // if onnxruntime upscaling wasn't performed return original region size
            } else {
                region = image.extractArea(imageRegion.toImageRect())
                val completeRegionSize = IntSize(imageRegion.width, imageRegion.height)
                requireCompleteSize(region, completeRegionSize)
                return region.toReaderImageData(
                    completeResultSatisfiedSize(completeRegionSize, IntSize(scaleWidth, scaleHeight))
                )
            }
        } finally {
            resized?.close()
            region?.close()
        }
    }

    private suspend fun KomeliaImage.toReaderImageData(satisfiedSize: IntSize): ReaderImageData {
        if (this.pagesLoaded == 1) {
            val skiaBitmap = this.toSkiaBitmap()
            val image = Image.makeFromBitmap(skiaBitmap)
            skiaBitmap.close()
            return ReaderImageData(width, height, satisfiedSize, listOf(image), null)
        }

        val frames = mutableListOf<RenderImage>()
        val delays = pageDelays?.let { mutableListOf<Long>() }
        for (i in 0 until this.pagesLoaded) {
            val skiaBitmap = this.extractArea(
                ImageRect(
                    left = 0,
                    right = width,
                    top = pageHeight * i,
                    bottom = pageHeight * (i + 1),
                )
            ).toSkiaBitmap()
            val image = Image.makeFromBitmap(skiaBitmap)
            skiaBitmap.close()

            frames.add(image)
            delays?.add(this.pageDelays?.getOrNull(i)?.toLong() ?: defaultFrameDelay)
        }
        return ReaderImageData(width, pageHeight, satisfiedSize, frames, delays)
    }

    private fun requireCompleteSize(image: KomeliaImage, expectedSize: IntSize) {
        check(image.width == expectedSize.width && image.pageHeight == expectedSize.height) {
            "backend returned ${image.width} x ${image.pageHeight} for complete " +
                "${expectedSize.width} x ${expectedSize.height} result"
        }
    }

    private fun requireCompleteUpscale(source: KomeliaImage, upscaled: KomeliaImage): Int {
        check(upscaled.width >= source.width && upscaled.width % source.width == 0) {
            "upscale returned incomplete width ${upscaled.width} for source width ${source.width}"
        }
        val scaleRatio = upscaled.width / source.width
        check(upscaled.pageHeight == source.pageHeight * scaleRatio) {
            "upscale returned incomplete height ${upscaled.pageHeight} for source height ${source.pageHeight} " +
                "at ${scaleRatio}x"
        }
        return scaleRatio
    }

    private fun completeResultSatisfiedSize(actualSize: IntSize, requestedSize: IntSize) = IntSize(
        width = maxOf(actualSize.width, requestedSize.width),
        height = maxOf(actualSize.height, requestedSize.height),
    )

    private fun IntRect.toImageRect() = ImageRect(left = left, top = top, right = right, bottom = bottom)

    private class SkiaTiledPainter(
        private val tiles: List<ReaderImageTile>,
        private val upsamplingMode: UpsamplingMode,
        private val scaleFactor: Double,
        private val displaySize: IntSize,
        private val showDebugGrid: Boolean,
    ) : TiledPainter() {
        override val intrinsicSize: Size = displaySize.toSize()
        private val samplingMode = if (scaleFactor > 1.0) when (upsamplingMode) {
            UpsamplingMode.NEAREST -> SamplingMode.DEFAULT
            UpsamplingMode.BILINEAR -> SamplingMode.LINEAR
            UpsamplingMode.MITCHELL -> SamplingMode.MITCHELL
            UpsamplingMode.CATMULL_ROM -> SamplingMode.CATMULL_ROM
        } else SamplingMode.DEFAULT

        override fun DrawScope.onDraw() {
            tiles.forEach { tile ->
                if (tile.renderImage != null && !tile.renderImage.isClosed && tile.isVisible) {
                    val bitmap = tile.renderImage
                    drawContext.canvas.skiaCanvas.drawImageRect(
                        image = bitmap,
                        src = Rect.makeWH(
                            tile.size.width.toFloat(),
                            tile.size.height.toFloat()
                        ),
                        dst = tile.displayRegion.toSkiaRect(),
                        samplingMode = samplingMode,
                        paint = null,
                        strict = true
                    )

                    if (showDebugGrid) {
                        drawContext.canvas.drawRect(
                            tile.displayRegion,
                            Paint().apply {
                                style = PaintingStyle.Stroke
                                color = Color.Green

                            }
                        )
                    }
                }
            }
        }

        override fun withSamplingMode(upsamplingMode: UpsamplingMode): TiledPainter {
            return SkiaTiledPainter(
                tiles = tiles,
                upsamplingMode = upsamplingMode,
                scaleFactor = scaleFactor,
                displaySize = displaySize,
                showDebugGrid = showDebugGrid
            )
        }
    }

}
