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

internal class TilePixelLedger(
    hardPeakPixels: Long,
) {
    private val mutex = Mutex()
    private val liveHandles = mutableMapOf<TileHandle, Long>()
    private var livePixels = 0L
    private var reservedPixels = 0L
    private var hardPeakPixels = hardPeakPixels

    init {
        require(hardPeakPixels >= 0)
    }

    suspend fun setHardPeakPixels(hardPeakPixels: Long) {
        require(hardPeakPixels >= 0)
        withContext(NonCancellable) {
            mutex.withLock {
                removeClosedHandles()
                if (livePixels + reservedPixels > hardPeakPixels) {
                    throw TilePixelBudgetExceededException(
                        "cannot lower tile pixel peak to $hardPeakPixels while " +
                            "$livePixels live and $reservedPixels reserved pixels remain"
                    )
                }
                this@TilePixelLedger.hardPeakPixels = hardPeakPixels
            }
        }
    }

    suspend fun reserve(size: IntSize): PixelReservation? {
        val pixelCount = checkedPixelCount(size)
        return mutex.withLock {
            removeClosedHandles()
            if (!canAdmit(pixelCount)) return@withLock null

            reservedPixels += pixelCount
            PixelReservation(
                ledger = this,
                size = size,
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

    suspend fun attach(
        reservation: PixelReservation,
        tile: OwnedTile,
    ): OwnedTile? {
        val attached = withContext(NonCancellable) {
            mutex.withLock {
                require(reservation.ledger === this@TilePixelLedger) {
                    "pixel reservation belongs to a different ledger"
                }
                removeClosedHandles()
                if (!reservation.active) return@withLock false

                reservation.active = false
                reservedPixels -= reservation.pixelCount
                val actualSize = tile.handle.renderSize
                if (actualSize.width > reservation.size.width || actualSize.height > reservation.size.height) {
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
                require(reservation.ledger === this@TilePixelLedger) {
                    "pixel reservation belongs to a different ledger"
                }
                if (!reservation.active) return@withLock
                reservation.active = false
                reservedPixels -= reservation.pixelCount
            }
        }
    }

    suspend fun snapshot(): TilePixelLedgerSnapshot = mutex.withLock {
        removeClosedHandles()
        TilePixelLedgerSnapshot(
            livePixels = livePixels,
            reservedPixels = reservedPixels,
            hardPeakPixels = hardPeakPixels,
        )
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

internal data class TilePixelLedgerSnapshot(
    val livePixels: Long,
    val reservedPixels: Long,
    val hardPeakPixels: Long,
)

internal class PixelReservation internal constructor(
    internal val ledger: TilePixelLedger,
    internal val size: IntSize,
    internal val pixelCount: Long,
) {
    internal var active = true

    suspend fun close() = ledger.cancel(this)
}
