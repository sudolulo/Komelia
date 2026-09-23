package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val displaySize = IntSize(2588, 4000)
private val areaSize = IntSize(900, 1000)
private val targetTransformation = Transformation(offset = Offset(-656f, 1200f), scale = 2f)

class PanelTransitionGeometryTest {
    @Test
    fun `visible region at target zoom is bounded around the panel`() {
        val region = visibleDisplayRegion(
            displaySize = IntSize(1000, 2000),
            areaSize = IntSize(500, 500),
            transformation = Transformation(offset = Offset.Zero, scale = 2f),
        )

        assertEquals(IntRect(375, 875, 625, 1125), region)
    }

    @Test
    fun `visible region rejects non-positive transformation scale`() {
        assertFailsWith<IllegalArgumentException> {
            visibleDisplayRegion(
                displaySize = IntSize(1000, 2000),
                areaSize = IntSize(500, 500),
                transformation = Transformation(offset = Offset.Zero, scale = 0f),
            )
        }
    }

    @Test
    fun `the only requirement is the destination viewport at the target zoom`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            targetTransformation = targetTransformation,
        )

        assertEquals(IntRect(1397, 1150, 1847, 1650), requirements.destination.region)
        assertEquals(2f, requirements.destination.zoomFactor)
        assertEquals(targetTransformation, requirements.targetTransformation)
    }

    @Test
    fun `the destination region is clipped to display bounds`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            targetTransformation = Transformation(offset = Offset(-3000f, -3000f), scale = 2f),
        )

        val region = requirements.destination.region
        assertTrue(region.left >= 0, "left of $region")
        assertTrue(region.top >= 0, "top of $region")
        assertTrue(region.right <= displaySize.width, "right of $region")
        assertTrue(region.bottom <= displaySize.height, "bottom of $region")
    }
}
