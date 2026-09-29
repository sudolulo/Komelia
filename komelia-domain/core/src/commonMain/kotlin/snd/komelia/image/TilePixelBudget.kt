package snd.komelia.image

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Raised when a render cannot be admitted within the hard peak. Callers that have a bounded
 * fallback catch it; everything else surfaces it as ordinary renderer error state.
 */
class TilePixelBudgetExceededException(message: String) : IllegalStateException(message)

internal class TilePixelBudget(
    private val hardPeakPixels: Long,
) {
    private val mutex = Mutex()
    private val liveHandles = mutableMapOf<TileHandle, Long>()
    private var livePixels = 0L
    private var reservedPixels = 0L

    init {
        require(hardPeakPixels >= 0)
    }

    suspend fun reserve(width: Int, height: Int): PixelReservation? {
        val pixelCount = checkedPixelCount(IntSize(width, height))
        return mutex.withLock {
            removeClosedHandles()
            if (!canAdmit(pixelCount)) return@withLock null

            reservedPixels += pixelCount
            PixelReservation(
                budget = this,
                width = width,
                height = height,
                pixelCount = pixelCount,
            )
        }
    }

    suspend fun retain(handle: TileHandle): TileOwnership? {
        val ownership = handle.retain() ?: return null
        val admitted = withContext(NonCancellable) {
            mutex.withLock {
                removeClosedHandles()
                when {
                    liveHandles.containsKey(handle) -> true
                    !canAdmit(handle.pixelCount) -> false
                    else -> {
                        liveHandles[handle] = handle.pixelCount
                        livePixels += handle.pixelCount
                        true
                    }
                }
            }
        }

        if (!admitted) ownership.close()
        return ownership.takeIf { admitted }
    }

    internal suspend fun attach(
        reservation: PixelReservation,
        tile: OwnedTile,
    ): OwnedTile? {
        val attached = withContext(NonCancellable) {
            mutex.withLock {
                removeClosedHandles()
                if (!reservation.active) return@withLock false

                reservation.active = false
                reservedPixels -= reservation.pixelCount
                val actualSize = tile.handle.renderSize
                if (actualSize.width > reservation.width || actualSize.height > reservation.height) {
                    return@withLock false
                }

                if (!liveHandles.containsKey(tile.handle)) {
                    liveHandles[tile.handle] = tile.handle.pixelCount
                    livePixels += tile.handle.pixelCount
                }
                true
            }
        }

        if (!attached) tile.ownership.close()
        return tile.takeIf { attached }
    }

    internal suspend fun cancel(reservation: PixelReservation) {
        withContext(NonCancellable) {
            mutex.withLock {
                if (!reservation.active) return@withLock
                reservation.active = false
                reservedPixels -= reservation.pixelCount
            }
        }
    }

    private fun canAdmit(pixelCount: Long): Boolean {
        val usedPixels = livePixels + reservedPixels
        return pixelCount <= hardPeakPixels - usedPixels
    }

    private fun removeClosedHandles() {
        val closedHandles = liveHandles.keys.filter { it.isClosed }
        for (handle in closedHandles) {
            livePixels -= checkNotNull(liveHandles.remove(handle))
        }
    }
}

internal class PixelReservation internal constructor(
    private val budget: TilePixelBudget,
    internal val width: Int,
    internal val height: Int,
    internal val pixelCount: Long,
) {
    internal var active = true

    suspend fun attach(tile: OwnedTile): OwnedTile? = budget.attach(this, tile)

    suspend fun close() = budget.cancel(this)
}
