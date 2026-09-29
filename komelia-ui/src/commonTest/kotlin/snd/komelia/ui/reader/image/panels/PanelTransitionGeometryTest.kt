@file:Suppress("INVISIBLE_MEMBER", "INVISIBLE_REFERENCE")

package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toRect
import snd.komelia.image.ImageRect
import snd.komelia.image.ViewportRenderRequirement
import snd.komelia.image.visibilityWindowFor
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val displaySize = IntSize(2588, 4000)
private val areaSize = IntSize(900, 1000)
private val targetTransformation = Transformation(offset = Offset(-656f, 1200f), scale = 2f)
private val fixtureImageSize = IntSize(300, 300)
private val defaultPanels = listOf(
    ImageRect(left = 0, top = 0, right = 100, bottom = 100),
    ImageRect(left = 100, top = 100, right = 200, bottom = 200),
    ImageRect(left = 200, top = 200, right = 300, bottom = 300),
)

private fun identityTransformationForZoom(zoom: Float): Transformation =
    Transformation(offset = Offset.Zero, scale = zoom)

class PanelTransitionGeometryTest {
    @Test
    fun `a requirement without overscan covers exactly its region`() {
        val region = IntRect(left = 100, top = 100, right = 300, bottom = 400)
        val exact = ViewportRenderRequirement(region = region, zoomFactor = 3f, overscan = false)
        val displayArea = IntSize(1192, 1843)

        assertEquals(region.toRect(), exact.visibilityWindow(displayArea))
    }

    @Test
    fun `a requirement with overscan expands by half a viewport per side`() {
        val region = IntRect(left = 400, top = 600, right = 800, bottom = 1000)
        val overscanned = ViewportRenderRequirement(region = region, zoomFactor = 3f)
        val displayArea = IntSize(1192, 1843)

        assertEquals(visibilityWindowFor(region.toRect(), displayArea), overscanned.visibilityWindow(displayArea))
    }

    @Test
    fun `the rolling window holds previous current and next at panel zoom without overscan`() {
        val window = rollingPanelRequirements(
            panels = defaultPanels,
            currentPanelIndex = 1,
            areaSize = IntSize(1192, 1843),
            targetSize = IntSize(1192, 1843),
            transformationForZoom = ::identityTransformationForZoom,
            imageSize = fixtureImageSize,
        )

        assertEquals(3, window.size)
        assertEquals(
            listOf(
                IntRect(left = 0, top = 0, right = 397, bottom = 614),
                IntRect(left = 397, top = 614, right = 794, bottom = 1228),
                IntRect(left = 795, top = 1229, right = 1192, bottom = 1843),
            ),
            window.map { it.region },
        )
        assertTrue(window.all { !it.overscan })
        assertTrue(window.all { it.zoomFactor > 1f })
    }

    @Test
    fun `the rolling window renders at the resolved transformation scale`() {
        val window = rollingPanelRequirements(
            panels = defaultPanels,
            currentPanelIndex = 1,
            areaSize = IntSize(300, 300),
            targetSize = IntSize(600, 600),
            transformationForZoom = { zoom ->
                Transformation(offset = Offset.Zero, scale = zoom * 0.5f)
            },
            imageSize = fixtureImageSize,
        )

        assertEquals(listOf(1.5f, 1.5f, 1.5f), window.map { it.zoomFactor })
    }

    @Test
    fun `the rolling window clips to two entries at the first panel`() {
        val window = rollingPanelRequirements(
            panels = defaultPanels,
            currentPanelIndex = 0,
            areaSize = IntSize(1192, 1843),
            targetSize = IntSize(1192, 1843),
            transformationForZoom = ::identityTransformationForZoom,
            imageSize = fixtureImageSize,
        )

        assertEquals(2, window.size)
    }

    @Test
    fun `the rolling window clips to two entries at the last panel`() {
        val window = rollingPanelRequirements(
            panels = defaultPanels,
            currentPanelIndex = 2,
            areaSize = IntSize(1192, 1843),
            targetSize = IntSize(1192, 1843),
            transformationForZoom = ::identityTransformationForZoom,
            imageSize = fixtureImageSize,
        )

        assertEquals(2, window.size)
    }

    @Test
    fun `the rolling window is empty for a degenerate area size`() {
        val window = rollingPanelRequirements(
            panels = defaultPanels,
            currentPanelIndex = 1,
            areaSize = IntSize(0, 0),
            targetSize = IntSize(1192, 1843),
            transformationForZoom = ::identityTransformationForZoom,
            imageSize = fixtureImageSize,
        )

        assertEquals(emptyList(), window)
    }

    @Test
    fun `fit page requirement covers the full target at fit scale`() {
        assertEquals(
            ViewportRenderRequirement(
                region = IntRect(0, 0, 1192, 1843),
                zoomFactor = 0.75f,
            ),
            fitPageRequirement(targetSize = IntSize(1192, 1843), fitScale = 0.75f),
        )
    }

    @Test
    fun `fit page requirement rejects unusable geometry`() {
        assertEquals(null, fitPageRequirement(targetSize = IntSize.Zero, fitScale = 1f))
        assertEquals(null, fitPageRequirement(targetSize = IntSize(1192, 1843), fitScale = Float.NaN))
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
