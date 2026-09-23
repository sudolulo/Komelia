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
import kotlinx.coroutines.withTimeout
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

// the fixture page is 4096x4096 fitted into 3200x3200, so the fitted scale is 0.78125 and a
// 256 pixel source tile renders to 400 pixels at zoom 2 and to 500 pixels at zoom 2.5
private val lowDensityTile = IntSize(400, 400)
private val highDensityTile = IntSize(500, 500)
private val narrowPanel = IntRect(1_000, 1_000, 1_400, 1_400)
private val widePanel = IntRect(200, 200, 2_800, 2_800)

private fun movingViewport(index: Int): IntRect {
    val offset = index * 16
    return IntRect(1_900 + offset, 1_900, 2_700 + offset, 2_700)
}

private fun requirement(region: IntRect, zoomFactor: Float) =
    ViewportRenderRequirement(region = region, zoomFactor = zoomFactor)

internal class RenderedTile(
    val sourceBounds: IntRect,
    val renderSize: IntSize,
    val renderImage: RenderImage,
)

internal interface PreparationFixture : ReaderImage {
    val painterPublications: Int
    val currentTileCoverage: IntRect?
    val publishedRegions: List<IntRect>
    val barrierCompletionCounts: Map<Long, Int>
    val tileCloseCounts: List<Int>
    val renderedTiles: List<RenderedTile>
    val closedImages: List<RenderImage>
    val publishedTiles: List<List<RenderedTile>>
    fun blockPainterPublication()
    suspend fun awaitPainterPublicationAttempts(count: Int)
    suspend fun awaitRegionGeneration()
    suspend fun awaitPainterPublications(count: Int)
    suspend fun awaitRenderEntries(count: Int)
    fun releasePainterPublication()
    fun releaseRendering()
    fun blockRendering()
    fun releaseRenderBlock()
    fun failNextRender(error: Throwable)
    fun failRenderAfter(successes: Int, error: Throwable)
    fun triggerSettingsReload()
    fun changeRenderInputs()
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
            preparation.await().close()
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
            barrier.await().close()

            assertTrue(image.publishedRegions.contains(targetCorridor))
        } finally {
            image.close()
        }
    }

    @Test
    fun `prepared corridor remains installed until lease release then latest viewport runs`() = runTest {
        val image = preparationFixture()
        try {
            image.blockPainterPublication()
            val barrier = async { image.prepareUpdate(screenSize, 2f, targetCorridor) }
            image.awaitPainterPublicationAttempts(1)

            repeat(20) { image.requestUpdate(screenSize, 2f, movingViewport(it)) }
            image.releasePainterPublication()
            val lease = barrier.await()

            assertEquals(listOf(1), image.barrierCompletionCounts.values.toList())
            assertEquals(1, image.painterPublications)
            assertTrue(
                checkNotNull(image.currentTileCoverage).containsRect(targetCorridor),
                "the corridor must remain installed while its preparation lease is held",
            )

            lease.close()
            lease.close()
            image.awaitPainterPublications(2)
            assertTrue(
                checkNotNull(image.currentTileCoverage).containsRect(movingViewport(19)),
                "the latest deferred viewport must publish after lease release",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `existing matching tiles satisfy barrier without another painter`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, 2f, targetCorridor).close()
            val painterBefore = image.painter.value

            image.prepareUpdate(screenSize, 2f, targetCorridor).close()

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
    }

    @Test
    fun `closing during blocked replacement closes current and pending old tiles exactly once`() = runTest {
        val image = preparationFixture()
        image.prepareUpdate(screenSize, 2f, targetCorridor).close()
        image.blockPainterPublication()
        val replacement = async {
            image.prepareUpdate(screenSize, 2f, movingViewport(19))
        }
        image.awaitPainterPublicationAttempts(2)

        try {
            image.close()

            assertTrue(image.tileCloseCounts.isNotEmpty())
            assertTrue(
                image.tileCloseCounts.all { it == 1 },
                "expected every generated tile to close exactly once, got ${image.tileCloseCounts}",
            )
            withTimeout(2_000) {
                assertFailsWith<CancellationException> { replacement.await() }
            }
        } finally {
            replacement.cancelAndJoin()
        }
    }

    @Test
    fun `closing after replacement publication does not close retired tiles twice`() = runTest {
        val image = preparationFixture()
        image.prepareUpdate(screenSize, 2f, targetCorridor).close()
        image.prepareUpdate(screenSize, 2f, movingViewport(19)).close()

        image.close()

        assertTrue(image.tileCloseCounts.isNotEmpty())
        assertTrue(
            image.tileCloseCounts.all { it == 1 },
            "expected every generated tile to close exactly once, got ${image.tileCloseCounts}",
        )
    }

    @Test
    fun `settings reload does not reuse completed barrier token`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, 2f, targetCorridor).close()
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

    @Test
    fun `higher resolution tile satisfies lower resolution request`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2.5f))).close()
            val warm = image.renderedTiles.toList()
            assertTrue(warm.isNotEmpty())
            assertTrue(
                warm.all { it.renderSize == highDensityTile },
                "expected the warm pass to render at $highDensityTile, got ${warm.map { it.renderSize }.toSet()}",
            )

            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2f))).close()

            assertEquals(
                warm.size,
                image.renderedTiles.size,
                "a sufficiently resolved tile must not trigger another native render",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `lower resolution tile is upgraded before readiness`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2f))).close()
            val warm = image.renderedTiles.toList()
            assertTrue(warm.all { it.renderSize == lowDensityTile })

            image.prepareUpdate(
                screenSize,
                listOf(requirement(targetCorridor, 2f), requirement(narrowPanel, 2.5f)),
            ).close()
            val upgraded = image.renderedTiles.drop(warm.size)

            assertTrue(upgraded.isNotEmpty(), "under-resolved tiles must be re-rendered")
            assertTrue(
                upgraded.size < warm.size,
                "only tiles the high zoom requirement covers may be re-rendered, got ${upgraded.size} of ${warm.size}",
            )
            assertTrue(upgraded.all { it.renderSize == highDensityTile })
            assertTrue(upgraded.all { tile -> warm.any { it.sourceBounds == tile.sourceBounds } })
        } finally {
            image.close()
        }
    }

    @Test
    fun `source bounds or fitted geometry mismatch prevents reuse`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2.5f))).close()
            val warm = image.renderedTiles.toList()
            assertTrue(warm.all { it.renderSize == highDensityTile })

            // same source tiles and the same display pixel area, but a different fitted geometry:
            // 4096 fitted into 4000x2560 scales by 0.625, so a 256 pixel source tile renders to 400
            // pixels. The retained 500 pixel tile is large enough yet belongs to the old geometry.
            image.prepareUpdate(
                IntSize(4_000, 2_560),
                listOf(requirement(IntRect(640, 640, 1_280, 1_280), 2.5f)),
            ).close()
            val refreshed = image.renderedTiles.drop(warm.size)

            assertTrue(refreshed.isNotEmpty(), "a fitted geometry change must invalidate reuse")
            assertTrue(
                refreshed.all { it.renderSize == IntSize(400, 400) },
                "expected re-render at the new geometry, got ${refreshed.map { it.renderSize }.toSet()}",
            )
            assertTrue(refreshed.all { tile -> warm.any { it.sourceBounds == tile.sourceBounds } })
        } finally {
            image.close()
        }
    }

    @Test
    fun `sampling change invalidates retained generation`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2f))).close()
            val warm = image.renderedTiles.toList()
            val publicationsBefore = image.painterPublications

            image.triggerSettingsReload()
            image.awaitPainterPublications(publicationsBefore + 1)
            val refreshed = image.renderedTiles.drop(warm.size)

            assertEquals(
                warm.map { it.sourceBounds }.toSet(),
                refreshed.map { it.sourceBounds }.toSet(),
                "a producing input change must re-render every retained tile",
            )
            assertTrue(refreshed.all { it.renderSize == lowDensityTile })

            val renderedAfterReload = image.renderedTiles.size
            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2f))).close()

            assertEquals(
                renderedAfterReload,
                image.renderedTiles.size,
                "tiles rendered under the new generation must be retained",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `stale generation completion closes without publication`() = runTest {
        val image = preparationFixture()
        try {
            image.blockRendering()
            val barrier = async { image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2f))) }
            image.awaitRenderEntries(1)

            image.changeRenderInputs()
            image.releaseRenderBlock()
            barrier.await().close()

            val abandoned = image.renderedTiles.filter { tile ->
                image.closedImages.any { it === tile.renderImage }
            }
            assertEquals(1, abandoned.size, "only the in-flight stale render may be released")
            assertTrue(
                image.publishedTiles.none { frame -> frame.any { it.renderImage === abandoned.single().renderImage } },
                "a stale render must never reach a painter",
            )
            assertTrue(image.painterPublications >= 1)
        } finally {
            image.close()
        }
    }

    @Test
    fun `high zoom source remains resolved during low zoom destination preparation`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, listOf(requirement(narrowPanel, 2.5f))).close()
            val sourceTiles = image.renderedTiles.toList()
            assertTrue(sourceTiles.isNotEmpty())
            assertTrue(sourceTiles.all { it.renderSize == highDensityTile })

            image.prepareUpdate(
                screenSize,
                listOf(requirement(narrowPanel, 2.5f), requirement(widePanel, 2f)),
            ).close()
            val added = image.renderedTiles.drop(sourceTiles.size)

            assertTrue(added.isNotEmpty(), "the destination viewport must render its own coverage")
            assertTrue(
                added.none { tile -> sourceTiles.any { it.sourceBounds == tile.sourceBounds } },
                "the wide low zoom viewport must not re-render the sharp source tiles",
            )
            assertTrue(added.all { it.renderSize == lowDensityTile })
            assertTrue(
                image.publishedTiles.last().any { it.renderSize == highDensityTile },
                "the published frame must keep the high zoom source tiles",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `demand render failure preserves current frame and releases temporary ownership`() = runTest {
        val image = preparationFixture()
        try {
            image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2f))).close()
            val warm = image.renderedTiles.toList()
            val painterBefore = image.painter.value
            val failure = IllegalStateException("tile render failed")
            image.failRenderAfter(successes = 2, error = failure)

            val observed = assertFailsWith<IllegalStateException> {
                image.prepareUpdate(screenSize, listOf(requirement(targetCorridor, 2.5f)))
            }

            assertSame(failure, observed)
            assertSame(painterBefore, image.painter.value, "a failed demand render must keep the current frame")

            val temporary = image.renderedTiles.drop(warm.size)
            assertEquals(2, temporary.size)
            assertTrue(
                temporary.all { tile -> image.closedImages.count { it === tile.renderImage } == 1 },
                "every temporarily owned tile must be released exactly once",
            )
            assertTrue(
                warm.none { tile -> image.closedImages.any { it === tile.renderImage } },
                "the visible frame's tiles must survive a failed render",
            )
        } finally {
            image.close()
        }
    }
}

private fun IntRect.containsRect(other: IntRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

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
    override val currentTileCoverage: IntRect? get() = delegate.currentTileCoverage
    override val publishedRegions: List<IntRect> get() = delegate.publishedRegions
    override val tileCloseCounts: List<Int> get() = delegate.tileCloseCounts
    override val renderedTiles: List<RenderedTile> get() = delegate.renderedTiles
    override val closedImages: List<RenderImage> get() = delegate.closedRenderImages
    override val publishedTiles: List<List<RenderedTile>> get() = delegate.publishedTiles
    override val barrierCompletionCounts: Map<Long, Int>
        get() = completions.mapValues { (_, count) ->
            count + if (delegate.reusedCompletedBarrier) 1 else 0
        }

    override fun requestUpdate(maxDisplaySize: IntSize, zoomFactor: Float, visibleDisplaySize: IntRect) {
        delegate.requestUpdate(maxDisplaySize, zoomFactor, visibleDisplaySize)
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease {
        val barrierId = nextBarrierId.incrementAndGet()
        val lease = try {
            delegate.prepareUpdate(maxDisplaySize, requirements)
        } catch (e: Throwable) {
            delegate.error.value?.let { throw it }
            throw e
        }
        completions.compute(barrierId) { _, count -> (count ?: 0) + 1 }
        return lease
    }

    override suspend fun getOriginalImageSize(): Result<IntSize> = delegate.getOriginalImageSize()
    override suspend fun getOriginalImage(): Result<KomeliaImage> = delegate.getOriginalImage()
    override fun blockPainterPublication() = delegate.blockPainterPublication()
    override suspend fun awaitPainterPublicationAttempts(count: Int) =
        delegate.awaitPainterPublicationAttempts(count)
    override suspend fun awaitRegionGeneration() = delegate.awaitRegionGeneration()
    override suspend fun awaitPainterPublications(count: Int) = delegate.awaitPainterPublications(count)
    override suspend fun awaitRenderEntries(count: Int) = delegate.awaitRenderEntries(count)
    override fun releasePainterPublication() = delegate.releasePainterPublication()
    override fun releaseRendering() = delegate.releaseRendering()
    override fun blockRendering() = delegate.blockRendering()
    override fun releaseRenderBlock() = delegate.releaseRenderBlock()
    override fun failNextRender(error: Throwable) = delegate.failNextRender(error)
    override fun failRenderAfter(successes: Int, error: Throwable) =
        delegate.failRenderAfter(successes, error)
    override fun triggerSettingsReload() = delegate.triggerSettingsReload()
    override fun changeRenderInputs() = delegate.changeRenderInputs()
    override fun close() = delegate.close()
}

private class ControllableTilingReaderImage private constructor(
    private val linearLight: MutableStateFlow<Boolean>,
) : TilingReaderImage(
    imageSource = ImageSource.MemorySource(byteArrayOf(0)),
    imageDecoder = FixtureDecoder,
    processingPipeline = ImageProcessingPipeline(),
    stretchImages = MutableStateFlow(false),
    upsamplingMode = MutableStateFlow(UpsamplingMode.BILINEAR),
    downSamplingKernel = MutableStateFlow(ReduceKernel.DEFAULT),
    linearLightDownSampling = linearLight,
    pageId = ReaderImage.PageId("preparation", 1),
) {
    constructor() : this(MutableStateFlow(false))

    private val publicationCount = MutableStateFlow(0)
    private val publicationAttemptCount = MutableStateFlow(0)
    private val generatedRegions = MutableStateFlow(0)
    private val renderEntryCount = MutableStateFlow(0)
    private val renderedRegions = CopyOnWriteArrayList<IntRect>()
    private val generatedTiles = CopyOnWriteArrayList<RenderedTile>()
    private val closedImages = CopyOnWriteArrayList<RenderImage>()
    private val publishedFrames = CopyOnWriteArrayList<List<RenderedTile>>()
    private val installedTileCoverage = AtomicReference<IntRect?>(null)
    private val publicationGate = AtomicReference(CountDownLatch(0))
    private val renderingGate = CountDownLatch(1)
    private val renderGate = AtomicReference(CountDownLatch(0))
    private val renderFailure = AtomicReference<Throwable?>(null)
    private val remainingSuccesses = AtomicLong(0)
    private val completedBarrierReuse = AtomicBoolean(false)

    val painterPublications: Int get() = publicationCount.value
    val currentTileCoverage: IntRect? get() = installedTileCoverage.get()
    val publishedRegions: List<IntRect> get() = renderedRegions.toList()
    val renderedTiles: List<RenderedTile> get() = generatedTiles.toList()
    val closedRenderImages: List<RenderImage> get() = closedImages.toList()
    val publishedTiles: List<List<RenderedTile>> get() = publishedFrames.toList()
    val tileCloseCounts: List<Int>
        get() = generatedTiles.map { generated -> closedImages.count { it === generated.renderImage } }
    val reusedCompletedBarrier: Boolean get() = completedBarrierReuse.get()

    fun blockPainterPublication() {
        publicationGate.set(CountDownLatch(1))
    }

    suspend fun awaitPainterPublicationAttempts(count: Int) {
        publicationAttemptCount.first { it >= count }
    }

    suspend fun awaitRegionGeneration() {
        generatedRegions.first { it > 0 }
    }

    suspend fun awaitPainterPublications(count: Int) {
        publicationCount.first { it >= count }
    }

    suspend fun awaitRenderEntries(count: Int) {
        renderEntryCount.first { it >= count }
    }

    fun releasePainterPublication() {
        publicationGate.get().countDown()
    }

    fun releaseRendering() {
        renderingGate.countDown()
    }

    fun blockRendering() {
        renderGate.set(CountDownLatch(1))
    }

    fun releaseRenderBlock() {
        renderGate.get().countDown()
    }

    fun failNextRender(error: Throwable) {
        failRenderAfter(successes = 0, error = error)
    }

    fun failRenderAfter(successes: Int, error: Throwable) {
        remainingSuccesses.set(successes.toLong())
        renderFailure.set(error)
    }

    /** changes a producing input the way a settings change does, and lets the reader reload */
    fun triggerSettingsReload() {
        if (lastUpdateRequest?.readiness?.isCompleted == true) {
            completedBarrierReuse.set(true)
        }
        linearLight.value = !linearLight.value
    }

    /**
     * Changes a producing input without waiting for the reader to react, so a render already
     * in flight completes against a generation that no longer matches.
     */
    fun changeRenderInputs() {
        linearLight.value = !linearLight.value
    }

    override fun closeRenderImage(renderImage: RenderImage) {
        closedImages += renderImage
        runCatching { renderImage.close() }
    }

    override fun createTilePainter(
        tiles: List<ReaderImageTile>,
        displaySize: IntSize,
        scaleFactor: Double,
    ): TiledPainter {
        publicationAttemptCount.value += 1
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
        installedTileCoverage.set(IntRect(left, top, right, bottom))
        publishedFrames += tiles.mapNotNull { tile ->
            generatedTiles.firstOrNull { it.renderImage === tile.renderImage }
        }
        publicationCount.value += 1
        return FixturePainter()
    }

    override suspend fun resizeImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        renderingGate.await()
        failIfRequested()
        return ReaderImageData(
            scaleWidth,
            scaleHeight,
            listOf(trackedRenderImage(IntRect(0, 0, image.width, image.pageHeight), scaleWidth, scaleHeight)),
            null,
        )
    }

    override suspend fun getImageRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        renderEntryCount.value += 1
        renderGate.get().await()
        failIfRequested()
        if (scaleWidth > 512) renderingGate.await()
        generatedRegions.value += 1
        return ReaderImageData(
            scaleWidth,
            scaleHeight,
            listOf(trackedRenderImage(imageRegion, scaleWidth, scaleHeight)),
            null,
        )
    }

    private fun failIfRequested() {
        val failure = renderFailure.get() ?: return
        if (remainingSuccesses.getAndUpdate { if (it > 0) it - 1 else it } > 0) return
        renderFailure.set(null)
        throw failure
    }

    private fun trackedRenderImage(
        sourceBounds: IntRect,
        scaleWidth: Int,
        scaleHeight: Int,
    ): RenderImage {
        val image = renderImage()
        generatedTiles += RenderedTile(sourceBounds, IntSize(scaleWidth, scaleHeight), image)
        return image
    }

    override fun close() {
        super.close()
        publicationGate.get().countDown()
        renderingGate.countDown()
        renderGate.get().countDown()
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
