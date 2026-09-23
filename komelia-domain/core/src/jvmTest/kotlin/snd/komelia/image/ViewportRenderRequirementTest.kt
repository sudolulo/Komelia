package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private val displayArea = IntSize(2_048, 2_048)

// visibility window: Rect(0, 0, 768, 768)
private val sourceRequirement = ViewportRenderRequirement(
    region = IntRect(0, 0, 512, 512),
    zoomFactor = 3f,
)

// visibility window: Rect(384, 384, 1920, 1920)
private val targetRequirement = ViewportRenderRequirement(
    region = IntRect(768, 768, 1_536, 1_536),
    zoomFactor = 1.5f,
)

private val sourceOnlyTile = IntRect(0, 0, 256, 256)

// inside both visibility windows, so both requirements apply to it
private val sharedTile = IntRect(512, 512, 768, 768)
private val targetOnlyTile = IntRect(1_024, 1_024, 1_280, 1_280)
private val outsideTile = IntRect(1_920, 1_920, 2_048, 2_048)

class ViewportRenderRequirementTest {
    @Test
    fun `tile covered by both requirements takes the highest required density`() {
        assertEquals(
            IntSize(768, 768),
            requiredSize(sharedTile, listOf(sourceRequirement, targetRequirement)),
        )
    }

    @Test
    fun `source only tile takes the source density`() {
        assertEquals(
            IntSize(768, 768),
            requiredSize(sourceOnlyTile, listOf(sourceRequirement, targetRequirement)),
        )
    }

    @Test
    fun `destination only tile takes the destination density`() {
        assertEquals(
            IntSize(384, 384),
            requiredSize(targetOnlyTile, listOf(sourceRequirement, targetRequirement)),
        )
    }

    @Test
    fun `requirement order does not change the required density`() {
        assertEquals(
            requiredSize(sharedTile, listOf(sourceRequirement, targetRequirement)),
            requiredSize(sharedTile, listOf(targetRequirement, sourceRequirement)),
        )
    }

    @Test
    fun `tile outside every requirement needs no render`() {
        assertNull(requiredSize(outsideTile, listOf(sourceRequirement, targetRequirement)))
    }

    @Test
    fun `required dimensions round at half pixel boundaries`() {
        val tile = IntRect(0, 0, 5, 3)
        val requirement = ViewportRenderRequirement(region = IntRect(0, 0, 8, 8), zoomFactor = 1f)

        assertEquals(
            IntSize(8, 5),
            requiredTileRenderSize(
                tileSourceBounds = tile,
                tileDisplayRegion = Rect(0f, 0f, 5f, 3f),
                displayScaleFactor = 1.5,
                displayArea = IntSize(16, 16),
                requirements = listOf(requirement),
            ),
        )
    }

    @Test
    fun `required dimensions never round below a single pixel`() {
        val tile = IntRect(0, 0, 1, 1)
        val requirement = ViewportRenderRequirement(region = IntRect(0, 0, 8, 8), zoomFactor = 1f)

        assertEquals(
            IntSize(1, 1),
            requiredTileRenderSize(
                tileSourceBounds = tile,
                tileDisplayRegion = Rect(0f, 0f, 1f, 1f),
                displayScaleFactor = 0.01,
                displayArea = IntSize(16, 16),
                requirements = listOf(requirement),
            ),
        )
    }

    @Test
    fun `non finite zoom factor is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ViewportRenderRequirement(region = IntRect(0, 0, 8, 8), zoomFactor = Float.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            ViewportRenderRequirement(region = IntRect(0, 0, 8, 8), zoomFactor = Float.POSITIVE_INFINITY)
        }
    }

    @Test
    fun `non positive zoom factor is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ViewportRenderRequirement(region = IntRect(0, 0, 8, 8), zoomFactor = 0f)
        }
    }

    @Test
    fun `empty region is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            ViewportRenderRequirement(region = IntRect(4, 4, 4, 12), zoomFactor = 1f)
        }
    }

    @Test
    fun `output dimensions beyond the integer range are rejected`() {
        val requirement = ViewportRenderRequirement(region = IntRect(0, 0, 8, 8), zoomFactor = 1f)

        assertFailsWith<IllegalArgumentException> {
            requiredTileRenderSize(
                tileSourceBounds = IntRect(0, 0, 1_000_000, 1_000_000),
                tileDisplayRegion = Rect(0f, 0f, 8f, 8f),
                displayScaleFactor = 1_000_000.0,
                displayArea = IntSize(16, 16),
                requirements = listOf(requirement),
            )
        }
    }

    @Test
    fun `empty requirement list is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            requiredTileRenderSize(
                tileSourceBounds = sourceOnlyTile,
                tileDisplayRegion = sourceOnlyTile.toDisplayRect(),
                displayScaleFactor = 1.0,
                displayArea = displayArea,
                requirements = emptyList(),
            )
        }
    }
}

private fun requiredSize(tile: IntRect, requirements: List<ViewportRenderRequirement>): IntSize? =
    requiredTileRenderSize(
        tileSourceBounds = tile,
        tileDisplayRegion = tile.toDisplayRect(),
        displayScaleFactor = 1.0,
        displayArea = displayArea,
        requirements = requirements,
    )

private fun IntRect.toDisplayRect() =
    Rect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
