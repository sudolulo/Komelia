package snd.komelia.ui.reader.image

import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenScaleStateTest {
    @Test
    fun `predicted zoom transformation equals setZoom result`() {
        val state = ScreenScaleState().apply {
            setAreaSize(IntSize(1080, 2208))
            setTargetSize(IntSize(1080, 1669).toSize())
        }
        val predicted = state.transformationForZoom(2.4f)

        state.setZoom(2.4f)

        assertEquals(predicted, state.transformation.value)
    }
}
