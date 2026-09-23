package snd.komelia.image

import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals

class RetainedPixelTallyTest {
    @Test
    fun `a handle two keys share counts once and is only given back by the last of them`() {
        val shared = handleOf(10, 10)
        val first = handleOf(4, 4)
        val second = handleOf(5, 5)
        // two distinct retained keys point at the same handle, so it must be counted once
        val tally = RetainedPixelTally(listOf(shared, first, shared, second))

        assertEquals(141L, tally.pixels, "100 shared plus 16 plus 25")
        assertEquals(141L, tally.remove(shared), "the second key still retains the shared handle")
        assertEquals(125L, tally.remove(first))
        assertEquals(25L, tally.remove(shared), "the last key retaining the shared handle is gone")
        assertEquals(0L, tally.remove(second))
    }

    @Test
    fun `the running total matches a full rescan after every removal`() {
        val shared = handleOf(10, 10)
        val retained = listOf(handleOf(3, 3), shared, handleOf(6, 6), shared, handleOf(7, 7))
        val tally = RetainedPixelTally(retained)
        assertEquals(uniquePixels(retained), tally.pixels)

        val remaining = retained.toMutableList()
        // an eviction order that drops one of the two shared references part way through, so a
        // total that subtracted the shared handle too early would drift from here on
        for (victim in listOf(shared, retained[2], shared, retained[0], retained[4])) {
            val running = tally.remove(victim)
            remaining.remove(victim)

            assertEquals(
                uniquePixels(remaining),
                running,
                "running total drifted from the retained set after evicting $victim",
            )
        }
        assertEquals(0L, tally.pixels)
    }

    @Test
    fun `removing a handle the tally never held leaves the total alone`() {
        val retained = listOf(handleOf(4, 4))
        val tally = RetainedPixelTally(retained)

        assertEquals(16L, tally.remove(handleOf(9, 9)))
    }
}

private fun handleOf(width: Int, height: Int): TileHandle =
    tileHandle(renderSize = IntSize(width, height)).handle

/** Independent restatement of the deduplicating scan the running total has to stay equal to. */
private fun uniquePixels(handles: List<TileHandle>): Long {
    val counted = mutableSetOf<TileHandle>()
    var pixels = 0L
    for (handle in handles) {
        if (counted.add(handle)) pixels += handle.pixelCount
    }
    return pixels
}
