package snd.komelia.image

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

class TilePixelBudgetTest {
    @Test
    fun `shared handle referenced by multiple owners counts once`() = runTest {
        val ledger = TilePixelLedger(hardPeakPixels = 16)
        val reservation = requireNotNull(ledger.reserve(IntSize(4, 4)))
        val created = tileHandle()
        val attached = requireNotNull(ledger.attach(reservation, created))
        val frame = requireNotNull(ledger.retain(attached.handle))
        val lease = requireNotNull(ledger.retain(attached.handle))

        assertNull(ledger.reserve(IntSize(1, 1)))
        attached.ownership.close()
        frame.close()
        assertNull(ledger.reserve(IntSize(1, 1)))

        lease.close()
        requireNotNull(ledger.reserve(IntSize(4, 4))).close()
    }

    @Test
    fun `reservation uses exact dimensions and checked Long totals`() = runTest {
        val exactLedger = TilePixelLedger(hardPeakPixels = 20)
        val fifteenPixels = requireNotNull(exactLedger.reserve(IntSize(3, 5)))
        val fivePixels = requireNotNull(exactLedger.reserve(IntSize(1, 5)))
        assertNull(exactLedger.reserve(IntSize(1, 1)))
        fifteenPixels.close()
        fivePixels.close()

        val maximumLedger = TilePixelLedger(hardPeakPixels = Long.MAX_VALUE)
        val maximumSize = IntSize(Int.MAX_VALUE, Int.MAX_VALUE)
        val first = requireNotNull(maximumLedger.reserve(maximumSize))
        val second = requireNotNull(maximumLedger.reserve(maximumSize))
        assertNull(maximumLedger.reserve(maximumSize))
        first.close()
        second.close()
    }

    @Test
    fun `reservation rejects non-positive dimensions`() = runTest {
        val ledger = TilePixelLedger(hardPeakPixels = 16)

        assertFailsWith<IllegalArgumentException> { ledger.reserve(IntSize(0, 4)) }
        assertFailsWith<IllegalArgumentException> { ledger.reserve(IntSize(4, -1)) }
    }

    @Test
    fun `second reservation crossing hard limit fails`() = runTest {
        val ledger = TilePixelLedger(hardPeakPixels = 16)
        val admitted = requireNotNull(ledger.reserve(IntSize(3, 5)))

        assertNull(ledger.reserve(IntSize(1, 2)))

        admitted.close()
    }

    @Test
    fun `reservation cancellation restores capacity`() = runTest {
        val ledger = TilePixelLedger(hardPeakPixels = 16)
        val reservation = requireNotNull(ledger.reserve(IntSize(4, 4)))

        assertNull(ledger.reserve(IntSize(1, 1)))
        reservation.close()
        reservation.close()

        requireNotNull(ledger.reserve(IntSize(4, 4))).close()
    }

    @Test
    fun `attaching retained handle atomically converts reservation`() = runTest {
        val ledger = TilePixelLedger(hardPeakPixels = 16)
        val reservation = requireNotNull(ledger.reserve(IntSize(4, 4)))
        val created = tileHandle()

        val attached = ledger.attach(reservation, created)

        assertSame(created, attached)
        assertNull(ledger.reserve(IntSize(1, 1)))
        created.ownership.close()
        requireNotNull(ledger.reserve(IntSize(4, 4))).close()
    }

    @Test
    fun `bitmap larger than reservation is rejected and closed`() = runTest {
        val ledger = TilePixelLedger(hardPeakPixels = 16)
        val reservation = requireNotNull(ledger.reserve(IntSize(4, 4)))
        val closed = AtomicInteger(0)
        val oversized = tileHandle(
            renderSize = IntSize(5, 4),
            onClose = { closed.incrementAndGet() },
        )

        assertNull(ledger.attach(reservation, oversized))
        assertEquals(1, closed.get())
        assertNull(oversized.handle.retain())
        requireNotNull(ledger.reserve(IntSize(4, 4))).close()
    }

    @Test
    fun `over budget reservation leaves existing live handles admitted`() = runTest {
        val ledger = TilePixelLedger(20_000)
        val existing = admittedTile(ledger, IntSize(100, 100))

        assertNull(ledger.reserve(IntSize(101, 100)))
        assertFalse(existing.handle.isClosed)
        assertEquals(10_000L, ledger.snapshot().livePixels)

        existing.ownership.close()
    }

    @Test
    fun `hard peak cannot be lowered below admitted pixels`() = runTest {
        val ledger = TilePixelLedger(20_000)
        val existing = admittedTile(ledger, IntSize(100, 100))

        assertFailsWith<TilePixelBudgetExceededException> {
            ledger.setHardPeakPixels(9_999)
        }
        assertEquals(20_000L, ledger.snapshot().hardPeakPixels)

        existing.ownership.close()
    }
}
