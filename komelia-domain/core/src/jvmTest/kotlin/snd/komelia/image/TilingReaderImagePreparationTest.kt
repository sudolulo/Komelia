package snd.komelia.image

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import snd.komelia.image.processing.ImageProcessingPipeline
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

private val screenSize = IntSize(3_200, 3_200)
private val targetCorridor = IntRect(800, 800, 1_600, 1_600)
private val firstViewport = IntRect(200, 200, 1_000, 1_000)

private fun movingViewport(index: Int): IntRect {
    val offset = index * 16
    return IntRect(1_900 + offset, 1_900, 2_700 + offset, 2_700)
}

internal interface PreparationFixture : ReaderImage {
    val painterPublications: Int
    val publishedRegions: List<IntRect>
    val barrierCompletionCounts: Map<Long, Int>
    fun blockPainterPublication()
    suspend fun awaitRegionGeneration()
    suspend fun awaitPainterPublications(count: Int)
    fun releasePainterPublication()
    fun releaseRendering()
    fun failNextRender(error: Throwable)
    fun triggerSettingsReload()
}

class TilingReaderImagePreparationTest {
    @Test
    fun `prepare update does not complete before matching painter is installed`() = runTest {
        val image = preparationFixture()
        try {
            image.blockPainterPublication()

            val preparation = async {
                image.prepareUpdate(screenSize, 2f, targetCorridor)
            }
            image.awaitRegionGeneration()

            assertFalse(preparation.isCompleted)
            image.releasePainterPublication()
            preparation.await()
            assertTrue(image.painter.value != null)
        } finally {
            image.close()
        }
    }

    @Test
    fun `conflated viewport requests cannot drop a barrier request`() = runTest {
        val image = preparationFixture()
        try {
            image.requestUpdate(screenSize, 1f, firstViewport)
            val barrier = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
            repeat(20) { image.requestUpdate(screenSize, 2f, movingViewport(it)) }

            image.releaseRendering()
            barrier.await()

            assertTrue(image.publishedRegions.contains(targetCorridor))
        } finally {
            image.close()
        }
    }

    @Test
    fun `existing matching tiles satisfy barrier without another painter`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, 2f, targetCorridor)
            val painterBefore = image.painter.value

            image.prepareUpdate(screenSize, 2f, targetCorridor)

            assertSame(painterBefore, image.painter.value)
            assertEquals(1, image.painterPublications)
        } finally {
            image.close()
        }
    }

    @Test
    fun `canceling waiter does not cancel shared image processing`() = runTest {
        val image = preparationFixture()
        try {
            image.blockPainterPublication()
            val waiter = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
            image.awaitRegionGeneration()

            waiter.cancelAndJoin()
            image.releasePainterPublication()
            image.awaitPainterPublications(1)

            assertTrue(image.publishedRegions.contains(targetCorridor))
        } finally {
            image.close()
        }
    }

    @Test
    fun `rendering failure completes barrier exceptionally and sets image error`() = runTest {
        val image = preparationFixture()
        try {
            val failure = IllegalStateException("region render failed")
            image.failNextRender(failure)

            val observed = assertFailsWith<IllegalStateException> {
                image.prepareUpdate(screenSize, 2f, targetCorridor)
            }

            assertSame(failure, image.error.value)
            assertSame(failure, observed)
        } finally {
            image.close()
        }
    }

    @Test
    fun `closing image terminates pending barrier`() = runTest {
        val image = preparationFixture()
        image.blockPainterPublication()
        val waiter = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
        image.awaitRegionGeneration()

        image.close()

        assertFailsWith<CancellationException> { waiter.await() }
        Unit
    }

    @Test
    fun `settings reload does not reuse completed barrier token`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, 2f, targetCorridor)
            val completedBeforeReload = image.barrierCompletionCounts.toMap()
            val publicationsBeforeReload = image.painterPublications

            image.triggerSettingsReload()
            image.awaitPainterPublications(publicationsBeforeReload + 1)

            assertEquals(completedBeforeReload, image.barrierCompletionCounts)
            assertTrue(image.barrierCompletionCounts.values.all { it == 1 })
        } finally {
            image.close()
        }
    }
}

private fun preparationFixture(): PreparationFixture {
    return DelegatingPreparationFixture(ControllableTilingReaderImage())
}

private class DelegatingPreparationFixture(
    private val delegate: ControllableTilingReaderImage,
) : PreparationFixture {
    private val nextBarrierId = AtomicLong()
    private val completions = ConcurrentHashMap<Long, Int>()

    override val pageId: ReaderImage.PageId get() = delegate.pageId
    override val originalSize: StateFlow<IntSize?> get() = delegate.originalSize
    override val displaySize: StateFlow<IntSize?> get() = delegate.displaySize
    override val currentSize: StateFlow<IntSize?> get() = delegate.currentSize
    override val painter: StateFlow<androidx.compose.ui.graphics.painter.Painter?> get() = delegate.painter
    override val error: StateFlow<Throwable?> get() = delegate.error
    override val painterPublications: Int get() = delegate.painterPublications
    override val publishedRegions: List<IntRect> get() = delegate.publishedRegions
    override val barrierCompletionCounts: Map<Long, Int>
        get() = completions.mapValues { (_, count) ->
            count + if (delegate.reusedCompletedBarrier) 1 else 0
        }

    override fun requestUpdate(maxDisplaySize: IntSize, zoomFactor: Float, visibleDisplaySize: IntRect) {
        delegate.requestUpdate(maxDisplaySize, zoomFactor, visibleDisplaySize)
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ) {
        val barrierId = nextBarrierId.incrementAndGet()
        try {
            delegate.prepareUpdate(maxDisplaySize, zoomFactor, visibleDisplaySize)
        } catch (e: Throwable) {
            delegate.error.value?.let { throw it }
            throw e
        }
        completions.compute(barrierId) { _, count -> (count ?: 0) + 1 }
    }

    override suspend fun getOriginalImageSize(): Result<IntSize> = delegate.getOriginalImageSize()
    override suspend fun getOriginalImage(): Result<KomeliaImage> = delegate.getOriginalImage()
    override fun blockPainterPublication() = delegate.blockPainterPublication()
    override suspend fun awaitRegionGeneration() = delegate.awaitRegionGeneration()
    override suspend fun awaitPainterPublications(count: Int) = delegate.awaitPainterPublications(count)
    override fun releasePainterPublication() = delegate.releasePainterPublication()
    override fun releaseRendering() = delegate.releaseRendering()
    override fun failNextRender(error: Throwable) = delegate.failNextRender(error)
    override fun triggerSettingsReload() = delegate.triggerSettingsReload()
    override fun close() = delegate.close()
}

private class ControllableTilingReaderImage : TilingReaderImage(
    imageSource = ImageSource.MemorySource(byteArrayOf(0)),
    imageDecoder = FixtureDecoder,
    processingPipeline = ImageProcessingPipeline(),
    stretchImages = MutableStateFlow(false),
    upsamplingMode = MutableStateFlow(UpsamplingMode.BILINEAR),
    downSamplingKernel = MutableStateFlow(ReduceKernel.DEFAULT),
    linearLightDownSampling = MutableStateFlow(false),
    pageId = ReaderImage.PageId("preparation", 1),
) {
    private val publicationCount = MutableStateFlow(0)
    private val generatedRegions = MutableStateFlow(0)
    private val renderedRegions = CopyOnWriteArrayList<IntRect>()
    private val publicationGate = AtomicReference(CountDownLatch(0))
    private val renderingGate = CountDownLatch(1)
    private val renderFailure = AtomicReference<Throwable?>(null)
    private val completedBarrierReuse = AtomicBoolean(false)

    val painterPublications: Int get() = publicationCount.value
    val publishedRegions: List<IntRect> get() = renderedRegions.toList()
    val reusedCompletedBarrier: Boolean get() = completedBarrierReuse.get()

    fun blockPainterPublication() {
        publicationGate.set(CountDownLatch(1))
    }

    suspend fun awaitRegionGeneration() {
        generatedRegions.first { it > 0 }
    }

    suspend fun awaitPainterPublications(count: Int) {
        publicationCount.first { it >= count }
    }

    fun releasePainterPublication() {
        publicationGate.get().countDown()
    }

    fun releaseRendering() {
        renderingGate.countDown()
    }

    fun failNextRender(error: Throwable) {
        renderFailure.set(error)
    }

    fun triggerSettingsReload() {
        if (lastUpdateRequest?.readiness?.isCompleted == true) {
            completedBarrierReuse.set(true)
        }
        processingScope.launch {
            lastUsedScaleFactor = null
            reloadLastRequest()
        }
    }

    override fun closeTileBitmaps(tiles: List<ReaderImageTile>) {
        tiles.forEach { tile -> runCatching { tile.renderImage?.close() } }
    }

    override fun createTilePainter(
        tiles: List<ReaderImageTile>,
        displaySize: IntSize,
        scaleFactor: Double,
    ): TiledPainter {
        publicationGate.get().await()
        val left = tiles.minOf { it.displayRegion.left }.roundToInt()
        val top = tiles.minOf { it.displayRegion.top }.roundToInt()
        val right = tiles.maxOf { it.displayRegion.right }.roundToInt()
        val bottom = tiles.maxOf { it.displayRegion.bottom }.roundToInt()
        val horizontalInset = (right - left) / 4
        val verticalInset = (bottom - top) / 4
        renderedRegions += IntRect(
            left + horizontalInset,
            top + verticalInset,
            right - horizontalInset,
            bottom - verticalInset,
        )
        publicationCount.value += 1
        return FixturePainter()
    }

    override suspend fun resizeImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        renderingGate.await()
        renderFailure.getAndSet(null)?.let { throw it }
        return ReaderImageData(scaleWidth, scaleHeight, listOf(renderImage()), null)
    }

    override suspend fun getImageRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        renderFailure.getAndSet(null)?.let { throw it }
        if (scaleWidth > 512) renderingGate.await()
        generatedRegions.value += 1
        return ReaderImageData(scaleWidth, scaleHeight, listOf(renderImage()), null)
    }

    override fun close() {
        super.close()
        publicationGate.get().countDown()
        renderingGate.countDown()
    }
}

private class FixturePainter : TilingReaderImage.TiledPainter() {
    override val intrinsicSize = Size.Zero
    override fun DrawScope.onDraw() = Unit
    override fun withSamplingMode(upsamplingMode: UpsamplingMode): TilingReaderImage.TiledPainter = this
}

private object FixtureDecoder : KomeliaImageDecoder {
    override suspend fun decode(encoded: ByteArray, nPages: Int?): KomeliaImage = FixtureImage
    override suspend fun decodeFromFile(path: String, nPages: Int?): KomeliaImage = FixtureImage

    override suspend fun decodeAndResize(
        encoded: ByteArray,
        scaleWidth: Int,
        scaleHeight: Int,
        crop: Boolean,
        nPages: Int?,
    ): KomeliaImage = FixtureImage

    override suspend fun decodeAndResize(
        path: String,
        scaleWidth: Int,
        scaleHeight: Int,
        crop: Boolean,
        nPages: Int?,
    ): KomeliaImage = FixtureImage
}

private object FixtureImage : KomeliaImage {
    override val width = 4_096
    override val height = 4_096
    override val bands = 4
    override val type = ImageFormat.RGBA_8888
    override val pagesLoaded = 1
    override val pagesTotal = 1
    override val pageHeight = 4_096
    override val pageDelays: IntArray? = null

    override suspend fun extractArea(rect: ImageRect): KomeliaImage = this
    override suspend fun resize(scaleWidth: Int, scaleHeight: Int, linear: Boolean, kernel: ReduceKernel) = this
    override suspend fun shrink(factor: Double): KomeliaImage = this
    override suspend fun findTrim() = ImageRect(0, 0, width, height)
    override suspend fun makeHistogram(): KomeliaImage = this
    override suspend fun mapLookupTable(table: ByteArray): KomeliaImage = this
    override suspend fun getBytes() = ByteArray(0)
    override fun close() = Unit
}

private fun renderImage(): Image {
    val bitmap = Bitmap()
    bitmap.allocPixels(
        ImageInfo(
            ColorInfo(ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB),
            4,
            4,
        )
    )
    val image = Image.makeFromBitmap(bitmap)
    bitmap.close()
    return image
}
