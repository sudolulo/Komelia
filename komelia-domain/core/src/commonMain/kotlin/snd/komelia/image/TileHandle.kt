package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.MutableStateFlow

internal class TileHandle private constructor(
    val renderImage: RenderImage,
    val sourceBounds: IntRect,
    val displayRegion: Rect,
    val renderSize: IntSize,
    val renderGeneration: Long,
    private val closeImage: (RenderImage) -> Unit,
) {
    private val references = MutableStateFlow(1)
    val pixelCount: Long = checkedPixelCount(renderSize)
    internal val isClosed: Boolean get() = references.value == 0

    fun retain(): TileOwnership? {
        while (true) {
            val current = references.value
            if (current == 0) return null
            check(current < Int.MAX_VALUE)
            if (references.compareAndSet(current, current + 1)) {
                return TileOwnership(this)
            }
        }
    }

    internal fun release() {
        while (true) {
            val current = references.value
            check(current > 0)
            if (references.compareAndSet(current, current - 1)) {
                if (current == 1) closeImage(renderImage)
                return
            }
        }
    }

    companion object {
        fun create(
            renderImage: RenderImage,
            sourceBounds: IntRect,
            displayRegion: Rect,
            renderSize: IntSize,
            renderGeneration: Long,
            closeImage: (RenderImage) -> Unit,
        ): OwnedTile {
            val handle = TileHandle(
                renderImage = renderImage,
                sourceBounds = sourceBounds,
                displayRegion = displayRegion,
                renderSize = renderSize,
                renderGeneration = renderGeneration,
                closeImage = closeImage,
            )
            return OwnedTile(handle, TileOwnership(handle))
        }
    }
}

internal class TileOwnership internal constructor(
    private val handle: TileHandle,
) : AutoCloseable {
    private val closed = MutableStateFlow(false)

    override fun close() {
        if (closed.compareAndSet(expect = false, update = true)) {
            handle.release()
        }
    }
}

internal data class OwnedTile(
    val handle: TileHandle,
    val ownership: TileOwnership,
)

internal fun checkedPixelCount(size: IntSize): Long {
    require(size.width > 0 && size.height > 0)
    val width = size.width.toLong()
    val height = size.height.toLong()
    check(width <= Long.MAX_VALUE / height)
    return width * height
}
