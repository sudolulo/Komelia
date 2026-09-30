package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Color
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect as SkiaRect
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LayeredTiledPainterTest {
    @Test
    fun `sharp overlay composites over the fitted page base`() {
        val base = solidImage(IntSize(100, 100), Color.BLUE)
        val overlay = solidImage(IntSize(20, 20), Color.RED)
        val surface = Surface.makeRasterN32Premul(100, 100)
        val pixels = Bitmap().apply { allocN32Pixels(100, 100, false) }
        try {
            val painter = createDesktopTiledPainterForTest(
                tiles = listOf(
                    TilingReaderImage.ReaderImageTile(
                        size = IntSize(100, 100),
                        displayRegion = Rect(0f, 0f, 100f, 100f),
                        isVisible = true,
                        renderImage = base,
                    ),
                    TilingReaderImage.ReaderImageTile(
                        size = IntSize(20, 20),
                        displayRegion = Rect(40f, 40f, 60f, 60f),
                        isVisible = true,
                        renderImage = overlay,
                    ),
                ),
                displaySize = IntSize(100, 100),
                scaleFactor = 1.0,
                upsamplingMode = UpsamplingMode.NEAREST,
            )
            CanvasDrawScope().draw(
                density = Density(1f),
                layoutDirection = LayoutDirection.Ltr,
                canvas = surface.canvas.asComposeCanvas(),
                size = Size(100f, 100f),
            ) {
                with(painter) { draw(size = size) }
            }

            assertTrue(surface.readPixels(pixels, 0, 0))
            assertEquals(Color.BLUE, pixels.getColor(10, 10))
            assertEquals(Color.RED, pixels.getColor(50, 50))
        } finally {
            pixels.close()
            surface.close()
            overlay.close()
            base.close()
        }
    }
}

private fun solidImage(size: IntSize, color: Int): RenderImage {
    val surface = Surface.makeRasterN32Premul(size.width, size.height)
    return try {
        surface.canvas.drawRect(
            SkiaRect.makeWH(size.width.toFloat(), size.height.toFloat()),
            Paint().apply { this.color = color },
        )
        surface.makeImageSnapshot()
    } finally {
        surface.close()
    }
}
