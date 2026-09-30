package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileHandleTest {
    @Test
    fun `a source capped tile satisfies the larger request it explicitly covered`() {
        val owned = tileHandle(renderSize = IntSize(256, 256), satisfiedSize = IntSize(768, 768))
        assertTrue(owned.handle.isSufficientFor(IntSize(768, 768)))
        assertEquals(256L * 256L, owned.handle.pixelCount)
        owned.ownership.close()
    }

    @Test
    fun `an arbitrary undersized tile does not satisfy a larger request`() {
        val owned = tileHandle(renderSize = IntSize(128, 128), satisfiedSize = IntSize(128, 128))
        assertFalse(owned.handle.isSufficientFor(IntSize(768, 768)))
        owned.ownership.close()
    }

    @Test
    fun `native image closes only after final ownership closes`() {
        val closed = AtomicInteger(0)
        val created = tileHandle(onClose = { closed.incrementAndGet() })
        val frame = requireNotNull(created.handle.retain())
        val lease = requireNotNull(created.handle.retain())

        created.ownership.close()
        frame.close()
        assertEquals(0, closed.get())
        lease.close()
        lease.close()
        assertEquals(1, closed.get())
        assertNull(created.handle.retain())
    }

    @Test
    fun `retain racing final close never owns a closed image`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(10_000) {
                val closed = AtomicInteger(0)
                val created = tileHandle(onClose = { closed.incrementAndGet() })
                val barrier = CyclicBarrier(3)
                val closing = executor.submit {
                    barrier.await()
                    created.ownership.close()
                }
                val retaining = executor.submit<TileOwnership?> {
                    barrier.await()
                    created.handle.retain()
                }

                barrier.await()
                closing.get()
                val retained = retaining.get()

                if (retained == null) {
                    assertEquals(1, closed.get())
                } else {
                    assertEquals(0, closed.get(), "a retained image must remain live")
                    retained.close()
                    assertEquals(1, closed.get())
                }
                assertNull(created.handle.retain())
            }
        } finally {
            executor.shutdownNow()
        }
    }
}

internal fun tileHandle(
    renderSize: IntSize = IntSize(4, 4),
    satisfiedSize: IntSize = renderSize,
    onClose: () -> Unit = {},
): OwnedTile {
    val image = renderImage(renderSize)
    return TileHandle.create(
        renderImage = image,
        sourceBounds = IntRect(0, 0, renderSize.width, renderSize.height),
        displayRegion = Rect(0f, 0f, renderSize.width.toFloat(), renderSize.height.toFloat()),
        renderSize = renderSize,
        satisfiedSize = satisfiedSize,
        renderGeneration = 1L,
        closeImage = {
            it.close()
            onClose()
        },
    )
}

private fun renderImage(size: IntSize): Image {
    val bitmap = Bitmap()
    bitmap.allocPixels(
        ImageInfo(
            ColorInfo(ColorType.RGBA_8888, ColorAlphaType.PREMUL, ColorSpace.sRGB),
            size.width,
            size.height,
        )
    )
    val image = Image.makeFromBitmap(bitmap)
    bitmap.close()
    return image
}
