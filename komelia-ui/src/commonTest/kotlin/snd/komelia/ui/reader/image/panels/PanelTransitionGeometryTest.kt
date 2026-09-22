package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PanelTransitionGeometryTest {
    @Test
    fun `corridor contains current view target zoom start destination and swept path`() {
        val current = IntRect(0, 100, 1100, 1500)
        val targetZoomStart = IntRect(0, 300, 900, 1300)
        val target = IntRect(1500, 300, 2400, 1300)

        val corridor = transitionCorridor(
            regions = listOf(current, targetZoomStart, target),
            displaySize = IntSize(2588, 4000),
        )

        assertEquals(IntRect(0, 100, 2400, 1500), corridor)
        assertContains(corridor, current)
        assertContains(corridor, targetZoomStart)
        assertContains(corridor, target)

        val startOffset = Offset(844f, 1200f)
        val targetOffset = Offset(-656f, 1200f)
        repeat(11) { sample ->
            val fraction = sample / 10f
            val offset = startOffset + (targetOffset - startOffset) * fraction
            val visibleRegion = visibleDisplayRegion(
                displaySize = IntSize(2588, 4000),
                areaSize = IntSize(900, 1000),
                transformation = Transformation(offset = offset, scale = 1f),
            )

            assertContains(corridor, visibleRegion)
        }
    }

    @Test
    fun `corridor clamps overscrolled endpoints to image bounds`() {
        val corridor = transitionCorridor(
            regions = listOf(
                IntRect(-200, -100, 700, 900),
                IntRect(1700, 3200, 2800, 4300),
            ),
            displaySize = IntSize(2588, 4000),
        )

        assertEquals(IntRect(0, 0, 2588, 4000), corridor)
    }

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
    fun `corridor requires a non-empty region`() {
        assertFailsWith<IllegalArgumentException> {
            transitionCorridor(
                regions = listOf(IntRect.Zero),
                displaySize = IntSize(2588, 4000),
            )
        }
    }

    private fun assertContains(container: IntRect, contained: IntRect) {
        assertTrue(container.left <= contained.left && container.top <= contained.top)
        assertTrue(container.right >= contained.right && container.bottom >= contained.bottom)
    }
}
