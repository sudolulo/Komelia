package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class TilingReaderImageTest {
    @Test
    fun `visibility window size does not grow with viewport position`() {
        val displayArea = IntSize(width = 2_000, height = 10_000)
        val nearTop = visibilityWindowFor(
            displayRegion = Rect(left = 400f, top = 500f, right = 800f, bottom = 1_100f),
            displayArea = displayArea,
        )
        val farDown = visibilityWindowFor(
            displayRegion = Rect(left = 400f, top = 5_000f, right = 800f, bottom = 5_600f),
            displayArea = displayArea,
        )

        assertEquals(Rect(left = 200f, top = 200f, right = 1_000f, bottom = 1_400f), nearTop)
        assertEquals(Rect(left = 200f, top = 4_700f, right = 1_000f, bottom = 5_900f), farDown)
        assertEquals(nearTop.width, farDown.width)
        assertEquals(nearTop.height, farDown.height)
    }
}
