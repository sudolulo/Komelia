package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val displaySize = IntSize(2588, 4000)
private val areaSize = IntSize(900, 1000)
private val sourceTransformation = Transformation(offset = Offset(844f, 1200f), scale = 1f)
private val zoomStartTransformation = Transformation(offset = Offset(844f, 1200f), scale = 2f)
private val targetTransformation = Transformation(offset = Offset(-656f, 1200f), scale = 2f)

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

    @Test
    fun `source requirement uses the source zoom`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = sourceTransformation,
            targetZoomStartTransformation = zoomStartTransformation,
            targetTransformation = targetTransformation,
        )

        assertEquals(IntRect(0, 300, 900, 1300), requirements.source.region)
        assertEquals(1f, requirements.source.zoomFactor)
    }

    @Test
    fun `zoom start and destination requirements use the target zoom`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = sourceTransformation,
            targetZoomStartTransformation = zoomStartTransformation,
            targetTransformation = targetTransformation,
        )

        assertEquals(IntRect(647, 1150, 1097, 1650), requirements.zoomStart.region)
        assertEquals(2f, requirements.zoomStart.zoomFactor)
        assertEquals(IntRect(1397, 1150, 1847, 1650), requirements.destination.region)
        assertEquals(2f, requirements.destination.zoomFactor)
    }

    @Test
    fun `swept requirement spans every endpoint at the highest zoom`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = sourceTransformation,
            targetZoomStartTransformation = zoomStartTransformation,
            targetTransformation = targetTransformation,
        )

        assertEquals(IntRect(0, 300, 1847, 1650), requirements.swept.region)
        assertEquals(2f, requirements.swept.zoomFactor)
        assertContains(requirements.swept.region, requirements.source.region)
        assertContains(requirements.swept.region, requirements.zoomStart.region)
        assertContains(requirements.swept.region, requirements.destination.region)
    }

    @Test
    fun `animated coverage carries both endpoints the zoom start and the swept path`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = sourceTransformation,
            targetZoomStartTransformation = zoomStartTransformation,
            targetTransformation = targetTransformation,
        )

        assertEquals(
            listOf(
                requirements.source,
                requirements.zoomStart,
                requirements.destination,
                requirements.swept,
            ),
            requirements.animatedRequirements,
        )
        assertEquals(targetTransformation, requirements.targetTransformation)
    }

    @Test
    fun `endpoint fallback omits swept coverage and keeps both endpoints complete`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = sourceTransformation,
            targetZoomStartTransformation = zoomStartTransformation,
            targetTransformation = targetTransformation,
        )

        assertEquals(
            listOf(requirements.source, requirements.destination),
            requirements.endpointRequirements,
        )
        assertFalse(requirements.endpointRequirements.contains(requirements.swept))
    }

    @Test
    fun `overlapping endpoints remain two requirements`() {
        // the source view and the destination view cover overlapping display regions at
        // different zooms; merging them would drop the destination's higher density
        val overlappingTarget = Transformation(offset = Offset(844f, 1200f), scale = 2f)
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = sourceTransformation,
            targetZoomStartTransformation = overlappingTarget,
            targetTransformation = overlappingTarget,
        )

        assertTrue(requirements.source.region.overlaps(requirements.destination.region))
        assertEquals(2, requirements.endpointRequirements.size)
        assertEquals(1f, requirements.endpointRequirements[0].zoomFactor)
        assertEquals(2f, requirements.endpointRequirements[1].zoomFactor)
    }

    @Test
    fun `requirement regions are clipped to display bounds`() {
        val requirements = panelTransitionRequirements(
            targetSize = displaySize,
            areaSize = areaSize,
            sourceTransformation = Transformation(offset = Offset(3000f, 4000f), scale = 1f),
            targetZoomStartTransformation = Transformation(offset = Offset(3000f, 4000f), scale = 2f),
            targetTransformation = Transformation(offset = Offset(-3000f, -3000f), scale = 2f),
        )

        for (requirement in requirements.animatedRequirements) {
            assertTrue(requirement.region.left >= 0, "left of ${requirement.region}")
            assertTrue(requirement.region.top >= 0, "top of ${requirement.region}")
            assertTrue(
                requirement.region.right <= displaySize.width,
                "right of ${requirement.region}",
            )
            assertTrue(
                requirement.region.bottom <= displaySize.height,
                "bottom of ${requirement.region}",
            )
        }
    }

    private fun assertContains(container: IntRect, contained: IntRect) {
        assertTrue(container.left <= contained.left && container.top <= contained.top)
        assertTrue(container.right >= contained.right && container.bottom >= contained.bottom)
    }
}
