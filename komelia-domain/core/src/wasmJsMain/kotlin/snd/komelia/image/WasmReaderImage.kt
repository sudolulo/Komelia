package snd.komelia.image

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow
import org.jetbrains.skia.Image
import snd.komelia.image.ReaderImage.PageId
import snd.komelia.image.processing.ImageProcessingPipeline

actual typealias RenderImage = Image

class WasmReaderImage(
    imageDecoder: KomeliaImageDecoder,
    imageSource: ImageSource,
    processingPipeline: ImageProcessingPipeline,
    stretchImages: StateFlow<Boolean>,
    upsamplingMode: StateFlow<UpsamplingMode>,
    downSamplingKernel: StateFlow<ReduceKernel>,
    linearLightDownSampling: StateFlow<Boolean>,
    pageId: PageId,
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

    override fun closeRenderImage(renderImage: RenderImage) {
        renderImage.close()
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

    override suspend fun resizeImage(image: KomeliaImage, scaleWidth: Int, scaleHeight: Int): ReaderImageData {
        image.resize(scaleWidth, scaleHeight).use { resized ->
            val requestedSize = IntSize(scaleWidth, scaleHeight)
            return resized.toReaderImageData(requestedSize).requireActualSize(requestedSize)
        }
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
                return region.toReaderImageData(IntSize(scaleWidth, scaleHeight))
                    .requireActualSize(IntSize(imageRegion.width, imageRegion.height))
            }
            resized = region.resize(scaleWidth, scaleHeight)
            val requestedSize = IntSize(scaleWidth, scaleHeight)
            return resized.toReaderImageData(requestedSize).requireActualSize(requestedSize)
        } finally {
            region?.close()
            resized?.close()
        }
    }

    private suspend fun KomeliaImage.toReaderImageData(satisfiedSize: IntSize): ReaderImageData {
        val bitmap = this.toBitmap()
        val image = Image.makeFromBitmap(bitmap)
        bitmap.close()
        return ReaderImageData(this.width, this.height, satisfiedSize, listOf(image), null)
    }

    private fun ReaderImageData.requireActualSize(expectedSize: IntSize): ReaderImageData {
        if (width == expectedSize.width && height == expectedSize.height) return this
        frames.forEach { closeRenderImage(it) }
        throw IllegalStateException(
            "backend returned $width x $height for complete ${expectedSize.width} x ${expectedSize.height} region"
        )
    }

    private fun IntRect.toImageRect() = ImageRect(left = left, top = top, right = right, bottom = bottom)
}
