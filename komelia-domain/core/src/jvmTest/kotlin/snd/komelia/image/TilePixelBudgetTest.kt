package snd.komelia.image

import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class TilePixelBudgetTest {
    @Test
    fun `shared handle referenced by multiple owners counts once`() = runTest {
        val budget = TilePixelBudget(hardPeakPixels = 16)
        val reservation = requireNotNull(budget.reserve(4, 4))
        val created = tileHandle()
        val attached = requireNotNull(reservation.attach(created))
        val frame = requireNotNull(budget.retain(attached.handle))
        val lease = requireNotNull(budget.retain(attached.handle))

        assertNull(budget.reserve(1, 1))
        attached.ownership.close()
        frame.close()
        assertNull(budget.reserve(1, 1))

        lease.close()
        requireNotNull(budget.reserve(4, 4)).close()
    }

    @Test
    fun `reservation uses exact dimensions and checked Long totals`() = runTest {
        val exactBudget = TilePixelBudget(hardPeakPixels = 20)
        val fifteenPixels = requireNotNull(exactBudget.reserve(3, 5))
        val fivePixels = requireNotNull(exactBudget.reserve(1, 5))
        assertNull(exactBudget.reserve(1, 1))
        fifteenPixels.close()
        fivePixels.close()

        val maximumBudget = TilePixelBudget(hardPeakPixels = Long.MAX_VALUE)
        val first = requireNotNull(maximumBudget.reserve(Int.MAX_VALUE, Int.MAX_VALUE))
        val second = requireNotNull(maximumBudget.reserve(Int.MAX_VALUE, Int.MAX_VALUE))
        assertNull(maximumBudget.reserve(Int.MAX_VALUE, Int.MAX_VALUE))
        first.close()
        second.close()
    }

    @Test
    fun `reservation rejects non-positive dimensions`() = runTest {
        val budget = TilePixelBudget(hardPeakPixels = 16)

        assertFailsWith<IllegalArgumentException> { budget.reserve(0, 4) }
        assertFailsWith<IllegalArgumentException> { budget.reserve(4, -1) }
    }

    @Test
    fun `second reservation crossing hard limit fails`() = runTest {
        val budget = TilePixelBudget(hardPeakPixels = 16)
        val admitted = requireNotNull(budget.reserve(3, 5))

        assertNull(budget.reserve(1, 2))

        admitted.close()
    }

    @Test
    fun `reservation cancellation restores capacity`() = runTest {
        val budget = TilePixelBudget(hardPeakPixels = 16)
        val reservation = requireNotNull(budget.reserve(4, 4))

        assertNull(budget.reserve(1, 1))
        reservation.close()
        reservation.close()

        requireNotNull(budget.reserve(4, 4)).close()
    }

    @Test
    fun `attaching retained handle atomically converts reservation`() = runTest {
        val budget = TilePixelBudget(hardPeakPixels = 16)
        val reservation = requireNotNull(budget.reserve(4, 4))
        val created = tileHandle()

        val attached = reservation.attach(created)

        assertSame(created, attached)
        assertNull(budget.reserve(1, 1))
        created.ownership.close()
        requireNotNull(budget.reserve(4, 4)).close()
    }

    @Test
    fun `bitmap larger than reservation is rejected and closed`() = runTest {
        val budget = TilePixelBudget(hardPeakPixels = 16)
        val reservation = requireNotNull(budget.reserve(4, 4))
        val closed = AtomicInteger(0)
        val oversized = tileHandle(
            renderSize = IntSize(5, 4),
            onClose = { closed.incrementAndGet() },
        )

        assertNull(reservation.attach(oversized))
        assertEquals(1, closed.get())
        assertNull(oversized.handle.retain())
        requireNotNull(budget.reserve(4, 4)).close()
    }
}
