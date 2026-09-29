package snd.komelia.image

/**
 * Unique live native pixels held by a retained working set, kept current as entries are removed
 * so an eviction loop does not rescan the whole set after every step.
 *
 * Several retained keys can point at one [TileHandle], and a shared handle's pixels count exactly
 * once. Removing a key therefore gives its pixels back only when it was the last key still
 * retaining that handle; subtracting any earlier would understate the working set and defeat the
 * hard-peak guarantee the eviction loop exists to hold.
 *
 * [TileHandle] carries no value equality, so the reference counts below are keyed by identity,
 * the same way the deduplicating scan they replace is.
 */
internal class RetainedPixelTally(handles: Collection<TileHandle>) {
    private val references = HashMap<TileHandle, Int>()

    var pixels = 0L
        private set

    init {
        for (handle in handles) {
            val existing = references[handle] ?: 0
            references[handle] = existing + 1
            if (existing == 0) pixels += handle.pixelCount
        }
    }

    /**
     * Drops one retaining reference to [handle]. A handle the tally does not hold is ignored,
     * so a caller that evicts the same key twice cannot drive the total negative.
     *
     * @return the unique pixel total after the removal
     */
    fun remove(handle: TileHandle): Long {
        val remaining = (references[handle] ?: return pixels) - 1
        if (remaining > 0) {
            references[handle] = remaining
        } else {
            references.remove(handle)
            pixels -= handle.pixelCount
        }
        return pixels
    }
}
