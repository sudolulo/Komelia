package snd.komelia.image

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import snd.komelia.image.processing.ImageProcessingPipeline
import snd.komelia.image.processing.ProcessingStep
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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

private val nearNeighbor = IntRect(0, 0, 800, 800)

private val smallScreen = IntSize(1_600, 1_600)

// expands to display 1400..1600 on both axes: the last tile of the grid, and so the last tile
// any pass renders
private val lastTilePanel = IntRect(1_450, 1_450, 1_550, 1_550)

// expands to the whole fitted page
private val wholeSmallPagePanel = IntRect(400, 400, 1_200, 1_200)

// a second fitted geometry: 4096 fitted into 2000x2000 tiles at zoom 3 into an 8x8 grid of 512
// pixel source tiles, each rendering to 750x750 and covering 250 display pixels. This panel
// expands to display 1800..2000 on both axes, which is the last tile of that grid alone.
private val largerScreen = IntSize(2_000, 2_000)
private val largerScreenPanel = IntRect(1_850, 1_850, 1_950, 1_950)

// a measured device page: 2588x4000 fitted into a 1198x1851 area is 2.2M display pixels at fit
// zoom, under the tiling threshold, so the fit pass renders one full page frame
private val devicePage = IntSize(2_588, 4_000)
private val deviceDisplay = IntSize(1_198, 1_851)
private val deviceFitPanel = IntRect(0, 0, 1_198, 1_851)

// the same screen one Compose layout pass later: calculateSizeForArea rounds a min ratio, so the
// fitted size drifts by three by four pixels with nothing visibly changing. At panel zoom it
// tiles, so this pass renders no page frame of its own to hide a discarded one.
private val driftedDisplay = IntSize(1_195, 1_847)
private val driftedPanel = IntRect(500, 800, 700, 1_000)

// unfolding the device: the fitted size grows by 35% on both axes, and still tiles at panel zoom
private val unfoldedDisplay = IntSize(1_618, 2_500)
private val unfoldedPanel = IntRect(600, 900, 800, 1_100)

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
    val hasRetainedPageFrame: Boolean
    fun replacePageImage()
    suspend fun awaitPageReplacement()
    fun releasePageReplacement()
    fun blockPainterPublication()
    suspend fun awaitPainterPublicationAttempts(count: Int)
    suspend fun awaitRegionGeneration()
    suspend fun awaitPainterPublications(count: Int)
    suspend fun awaitRenderEntries(count: Int)
    suspend fun awaitClosedImages(count: Int)
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
    fun `destination only rendering stays within the expected viewport expansion`() = runTest {
        // visibilityWindowFor expands by half of each dimension on each axis: 1.5W x 1.5H = 2.25x
        val rendered = renderDestinationViewport(displaySize = IntSize(1192, 1843), zoom = 3f)
        assertTrue(rendered.pixels <= (2.25 * 1192 * 1843 * 3 * 3).toLong())
    }

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
            // the cancellation cascade runs on the reader's real dispatcher, so this bound has
            // to be real time; runTest would otherwise skip a virtual 2s the instant it idles
            withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    assertFailsWith<CancellationException> { replacement.await() }
                }
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

    @Test
    fun `full resize output is retained after tiled rendering replaces it`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            // 1600x1600 fitted at zoom 1 is 2.56M display pixels, under the tiling threshold,
            // so this pass renders the whole page as a single full resize frame
            image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
            val pageFrame = image.renderedTiles.single()
            assertTrue(image.hasRetainedPageFrame, "a published full resize must retain a page frame")

            image.prepareUpdate(smallScreen, listOf(requirement(lastTilePanel, 3f))).close()

            assertEquals(2, image.renderedTiles.size, "the tiled pass must render its own tile")
            assertTrue(
                image.publishedTiles.last().none { it.renderImage === pageFrame.renderImage },
                "the tiled frame must have replaced the page frame",
            )
            assertEquals(
                0,
                image.closedImages.count { it === pageFrame.renderImage },
                "the retained page frame must stay live under the tiled frame",
            )
            assertTrue(image.hasRetainedPageFrame, "tiled rendering must not retire the page frame")
        } finally {
            image.close()
        }
    }

    @Test
    fun `retained page frame is unavailable before the first successful full resize`() = runTest {
        val image = preparationFixture()
        try {
            assertFalse(image.hasRetainedPageFrame, "a page that has not painted has no page frame")

            image.releaseRendering()
            image.prepareUpdate(smallScreen, listOf(requirement(lastTilePanel, 3f))).close()

            assertEquals(1, image.renderedTiles.size)
            assertFalse(
                image.hasRetainedPageFrame,
                "a tiled pass covers one viewport and must not be reported as a page frame",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `page replacement releases the retained page frame exactly once`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
            val outgoing = image.renderedTiles.single()
            // leaves the reader tiled at panel zoom, so nothing the replacement renders can
            // produce another page frame and hide a leak of this one
            image.prepareUpdate(smallScreen, listOf(requirement(lastTilePanel, 3f))).close()
            assertTrue(image.hasRetainedPageFrame)

            image.replacePageImage()
            image.awaitPageReplacement()

            assertFalse(
                image.hasRetainedPageFrame,
                "the outgoing image's page frame must stop being available as it is replaced",
            )
            assertEquals(
                1,
                image.closedImages.count { it === outgoing.renderImage },
                "the outgoing page frame must be released exactly once",
            )

            image.releasePageReplacement()
            image.awaitRenderEntries(2)

            assertTrue(
                image.tileCloseCounts.all { it <= 1 },
                "expected no tile to close twice, got ${image.tileCloseCounts}",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `generation or geometry change releases the retained page frame exactly once`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
            val outgoing = image.renderedTiles.single()

            // a different fitted geometry at panel zoom: the page frame of the previous
            // generation can no longer be drawn, and a tiled pass renders no replacement for it
            image.prepareUpdate(largerScreen, listOf(requirement(largerScreenPanel, 3f))).close()

            assertEquals(2, image.renderedTiles.size, "the new geometry must render its own tile")
            assertEquals(
                1,
                image.closedImages.count { it === outgoing.renderImage },
                "the page frame of the previous geometry must be released exactly once",
            )
            assertFalse(
                image.hasRetainedPageFrame,
                "a generation with no full resize of its own has no page frame",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `a full resize invalidated after retention rolls the page frame back`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()

            // valid right up until the page frame is retained; retaining it is what flips the
            // predicate false, which lands in the window between retention and frame publication
            assertFailsWith<CancellationException> {
                image.prepareUpdate(
                    smallScreen,
                    listOf(requirement(wholeSmallPagePanel, 1f)),
                    isStillValid = { !image.hasRetainedPageFrame },
                )
            }

            assertFalse(
                image.hasRetainedPageFrame,
                "a page frame retained by a resize invalidated before publication must be rolled back",
            )
            val outgoing = image.renderedTiles.single()
            assertEquals(
                1,
                image.closedImages.count { it === outgoing.renderImage },
                "the rolled-back page frame must be released exactly once, got ${image.tileCloseCounts}",
            )
            assertEquals(
                0,
                image.painterPublications,
                "an invalidated resize must not publish a frame",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `a few pixels of display drift keep the retained page frame`() = runTest {
        val image = preparationFixture(imageSize = devicePage)
        try {
            image.releaseRendering()
            image.prepareUpdate(deviceDisplay, listOf(requirement(deviceFitPanel, 1f))).close()
            val pageFrame = image.renderedTiles.single()
            assertTrue(image.hasRetainedPageFrame, "a fit zoom render must retain a page frame")

            image.prepareUpdate(driftedDisplay, listOf(requirement(driftedPanel, 3f))).close()

            assertTrue(
                image.hasRetainedPageFrame,
                "a three pixel display drift must not discard the retained page frame",
            )
            assertEquals(
                0,
                image.closedImages.count { it === pageFrame.renderImage },
                "the page frame that survived the drift must stay live",
            )
            val lease = assertNotNull(
                image.publishPageFrame(),
                "the frame that survived the drift must still be publishable",
            )
            assertEquals(
                listOf(pageFrame.renderImage),
                image.publishedTiles.last().map { it.renderImage },
                "the installed frame must be the retained page frame itself",
            )
            lease.close()
        } finally {
            image.close()
        }
    }

    @Test
    fun `a real display size change discards the retained page frame`() = runTest {
        val image = preparationFixture(imageSize = devicePage)
        try {
            image.releaseRendering()
            image.prepareUpdate(deviceDisplay, listOf(requirement(deviceFitPanel, 1f))).close()
            val pageFrame = image.renderedTiles.single()
            assertTrue(image.hasRetainedPageFrame)

            image.prepareUpdate(unfoldedDisplay, listOf(requirement(unfoldedPanel, 3f))).close()

            assertFalse(
                image.hasRetainedPageFrame,
                "a material display size change must discard the stale page frame",
            )
            assertEquals(
                1,
                image.closedImages.count { it === pageFrame.renderImage },
                "the stale page frame must be released exactly once",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `reader close releases the retained page frame exactly once`() = runTest {
        val image = preparationFixture()
        image.releaseRendering()
        image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
        assertTrue(image.hasRetainedPageFrame)

        image.close()

        assertFalse(image.hasRetainedPageFrame, "a closed reader holds no page frame")
        // the preparation lease releases its own reference on the reader's real dispatcher as
        // the cancellation unwinds, so the last reference to drop is not the closing thread's
        withContext(Dispatchers.Default) {
            withTimeout(2_000) { image.awaitClosedImages(1) }
        }
        assertEquals(listOf(1), image.tileCloseCounts)
    }

    @Test
    fun `publishing the page frame installs it without rendering`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
            val pageFrame = image.renderedTiles.single()
            image.prepareUpdate(smallScreen, listOf(requirement(lastTilePanel, 3f))).close()
            val renderedBeforePublication = image.renderedTiles.size
            val publicationsBefore = image.painterPublications

            val lease = assertNotNull(image.publishPageFrame(), "a retained page frame must be publishable")

            assertEquals(
                renderedBeforePublication,
                image.renderedTiles.size,
                "publishing an existing frame must not render anything",
            )
            assertEquals(publicationsBefore + 1, image.painterPublications)
            assertEquals(
                listOf(pageFrame.renderImage),
                image.publishedTiles.last().map { it.renderImage },
                "the installed frame must be the retained page frame itself",
            )
            lease.close()
        } finally {
            image.close()
        }
    }

    @Test
    fun `publishing the page frame is refused before the first full resize`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            // a tiled pass covers one viewport, so it leaves no frame that covers the page
            image.prepareUpdate(smallScreen, listOf(requirement(lastTilePanel, 3f))).close()
            val painterBefore = image.painter.value
            val renderedBefore = image.renderedTiles.size

            assertNull(image.publishPageFrame(), "an unavailable page frame must be refused, not rendered")

            assertEquals(renderedBefore, image.renderedTiles.size)
            assertSame(painterBefore, image.painter.value)
            assertNull(image.error.value)
        } finally {
            image.close()
        }
    }

    @Test
    fun `an ordinary viewport update does not publish while the page frame lease is held`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
            val pageFrame = image.renderedTiles.single()
            val lease = assertNotNull(image.publishPageFrame())
            val publicationsUnderLease = image.painterPublications

            image.requestUpdate(smallScreen, 3f, lastTilePanel)
            // long enough for the conflated lane to have run the request had it not been held
            withContext(Dispatchers.Default) { delay(200) }

            assertEquals(
                publicationsUnderLease,
                image.painterPublications,
                "an ordinary viewport update must not publish over the held page frame",
            )
            assertEquals(
                listOf(pageFrame.renderImage),
                image.publishedTiles.last().map { it.renderImage },
            )

            lease.close()
            image.awaitPainterPublications(publicationsUnderLease + 1)
            assertTrue(
                image.publishedTiles.last().none { it.renderImage === pageFrame.renderImage },
                "the deferred viewport must publish once the lease is released",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `releasing the page frame lease keeps the retained page frame alive`() = runTest {
        val image = preparationFixture()
        try {
            image.releaseRendering()
            image.prepareUpdate(smallScreen, listOf(requirement(wholeSmallPagePanel, 1f))).close()
            val pageFrame = image.renderedTiles.single()

            val lease = assertNotNull(image.publishPageFrame())
            lease.close()
            lease.close()

            assertTrue(image.hasRetainedPageFrame, "releasing the publication lock must not retire the frame")
            assertEquals(
                0,
                image.closedImages.count { it === pageFrame.renderImage },
                "the page frame must stay live after its lease is released",
            )
            assertTrue(
                image.tileCloseCounts.all { it <= 1 },
                "expected no tile to close twice, got ${image.tileCloseCounts}",
            )
        } finally {
            image.close()
        }
    }

    @Test
    fun `reader close drains queues and releases every ownership once`() = runTest {
        val image = preparationFixture()
        image.prepareUpdate(screenSize, listOf(requirement(narrowPanel, 2f))).close()

        image.blockPainterPublication()
        val barrier = async { image.prepareUpdate(screenSize, listOf(requirement(nearNeighbor, 2f))) }
        image.awaitPainterPublicationAttempts(2)
        val queuedDemand = async { image.prepareUpdate(screenSize, listOf(requirement(widePanel, 2f))) }
        yield()

        image.close()

        assertFailsWith<CancellationException> { barrier.await() }
        assertFailsWith<CancellationException> { queuedDemand.await() }
        assertTrue(image.tileCloseCounts.isNotEmpty())
        assertTrue(
            image.tileCloseCounts.all { it == 1 },
            "expected every generated tile to close exactly once, got ${image.tileCloseCounts}",
        )
    }
}

private data class RenderedViewport(val pixels: Long)

private suspend fun renderDestinationViewport(displaySize: IntSize, zoom: Float): RenderedViewport {
    val image = preparationFixture(imageSize = displaySize)
    val viewportWidth = (displaySize.width / zoom).roundToInt().coerceAtLeast(1)
    val viewportHeight = (displaySize.height / zoom).roundToInt().coerceAtLeast(1)
    val left = (displaySize.width - viewportWidth) / 2
    val top = (displaySize.height - viewportHeight) / 2
    val destination = IntRect(left, top, left + viewportWidth, top + viewportHeight)

    return try {
        image.releaseRendering()
        image.prepareUpdate(displaySize, listOf(requirement(destination, zoom))).close()
        RenderedViewport(
            pixels = image.renderedTiles.sumOf { tile ->
                tile.renderSize.width.toLong() * tile.renderSize.height
            }
        )
    } finally {
        image.close()
    }
}

private fun IntRect.containsRect(other: IntRect): Boolean =
    left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

private fun preparationFixture(imageSize: IntSize = IntSize(4_096, 4_096)): PreparationFixture {
    return DelegatingPreparationFixture(ControllableTilingReaderImage(imageSize))
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
    override val hasRetainedPageFrame: Boolean get() = delegate.hasRetainedPageFrame
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

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
        isStillValid: () -> Boolean,
    ): PreparedUpdateLease {
        val barrierId = nextBarrierId.incrementAndGet()
        val lease = try {
            delegate.prepareUpdate(maxDisplaySize, requirements, isStillValid)
        } catch (e: Throwable) {
            delegate.error.value?.let { throw it }
            throw e
        }
        completions.compute(barrierId) { _, count -> (count ?: 0) + 1 }
        return lease
    }

    override suspend fun publishPageFrame(): PreparedUpdateLease? = delegate.publishPageFrame()

    override suspend fun getOriginalImageSize(): Result<IntSize> = delegate.getOriginalImageSize()
    override suspend fun getOriginalImage(): Result<KomeliaImage> = delegate.getOriginalImage()
    override fun replacePageImage() = delegate.replacePageImage()
    override suspend fun awaitPageReplacement() = delegate.awaitPageReplacement()
    override fun releasePageReplacement() = delegate.releasePageReplacement()
    override fun blockPainterPublication() = delegate.blockPainterPublication()
    override suspend fun awaitPainterPublicationAttempts(count: Int) =
        delegate.awaitPainterPublicationAttempts(count)
    override suspend fun awaitRegionGeneration() = delegate.awaitRegionGeneration()
    override suspend fun awaitPainterPublications(count: Int) = delegate.awaitPainterPublications(count)
    override suspend fun awaitRenderEntries(count: Int) = delegate.awaitRenderEntries(count)
    override suspend fun awaitClosedImages(count: Int) = delegate.awaitClosedImages(count)
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
    private val processingPipeline: ImageProcessingPipeline,
    fixtureImage: KomeliaImage,
) : TilingReaderImage(
    imageSource = ImageSource.MemorySource(byteArrayOf(0)),
    imageDecoder = FixtureDecoder(fixtureImage),
    processingPipeline = processingPipeline,
    stretchImages = MutableStateFlow(false),
    upsamplingMode = MutableStateFlow(UpsamplingMode.BILINEAR),
    downSamplingKernel = MutableStateFlow(ReduceKernel.DEFAULT),
    linearLightDownSampling = linearLight,
    pageId = ReaderImage.PageId("preparation", 1),
) {
    constructor(imageSize: IntSize) : this(
        MutableStateFlow(false),
        ImageProcessingPipeline(),
        FixtureImage(imageSize.width, imageSize.height),
    )

    private val publicationCount = MutableStateFlow(0)
    private val publicationAttemptCount = MutableStateFlow(0)
    private val generatedRegions = MutableStateFlow(0)
    private val renderEntryCount = MutableStateFlow(0)
    private val closedImageCount = MutableStateFlow(0)
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
    private val pageReplacement = AtomicReference<PageReplacementStep?>(null)

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

    suspend fun awaitClosedImages(count: Int) {
        closedImageCount.first { it >= count }
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

    /**
     * Replaces the page's decoded image the way a processing pipeline change does: the reader
     * discards the current image, decodes it again and re-renders the last request against it.
     *
     * The reader parks inside the replacement so the state it leaves behind can be observed
     * before the re-render hides it. [releasePageReplacement] or [close] resumes it.
     */
    fun replacePageImage() {
        val step = PageReplacementStep()
        pageReplacement.set(step)
        processingPipeline.addStep(step)
    }

    suspend fun awaitPageReplacement() {
        checkNotNull(pageReplacement.get()) { "no page replacement was started" }.entered.first { it }
    }

    fun releasePageReplacement() {
        checkNotNull(pageReplacement.get()) { "no page replacement was started" }.resume()
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
        closedImageCount.update { it + 1 }
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
        pageReplacement.get()?.resume()
    }
}

/**
 * Fires the pipeline change flow and then parks the reader inside the replacement it triggers.
 * The decoded image itself is left as it is.
 */
private class PageReplacementStep : ProcessingStep {
    val entered = MutableStateFlow(false)
    private val gate = CountDownLatch(1)

    override suspend fun process(pageId: ReaderImage.PageId, image: KomeliaImage): KomeliaImage? {
        entered.value = true
        gate.await()
        return null
    }

    override suspend fun addChangeListener(callback: () -> Unit) = Unit

    fun resume() = gate.countDown()
}

private class FixturePainter : TilingReaderImage.TiledPainter() {
    override val intrinsicSize = Size.Zero
    override fun DrawScope.onDraw() = Unit
    override fun withSamplingMode(upsamplingMode: UpsamplingMode): TilingReaderImage.TiledPainter = this
}

private class FixtureDecoder(private val image: KomeliaImage) : KomeliaImageDecoder {
    override suspend fun decode(encoded: ByteArray, nPages: Int?): KomeliaImage = image
    override suspend fun decodeFromFile(path: String, nPages: Int?): KomeliaImage = image

    override suspend fun decodeAndResize(
        encoded: ByteArray,
        scaleWidth: Int,
        scaleHeight: Int,
        crop: Boolean,
        nPages: Int?,
    ): KomeliaImage = image

    override suspend fun decodeAndResize(
        path: String,
        scaleWidth: Int,
        scaleHeight: Int,
        crop: Boolean,
        nPages: Int?,
    ): KomeliaImage = image
}

private class FixtureImage(
    override val width: Int,
    override val height: Int,
) : KomeliaImage {
    override val bands = 4
    override val type = ImageFormat.RGBA_8888
    override val pagesLoaded = 1
    override val pagesTotal = 1
    override val pageHeight = height
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
