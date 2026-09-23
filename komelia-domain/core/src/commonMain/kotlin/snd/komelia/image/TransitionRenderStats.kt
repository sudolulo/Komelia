package snd.komelia.image

/**
 * What the renderer actually did to satisfy one demand-path transition request.
 *
 * Counts cover that call only. Prefetch renders are reported separately, because the
 * acceptance question is how much work a transition did *before movement started*.
 *
 * @param reusedTiles tiles served from retained coverage without a native render
 * @param upgradedTiles subset of [renderedTiles] that replaced an insufficiently resolved tile
 * @param renderedTiles native tile renders the call issued, upgrades included
 * @param evictedTiles retained working-set entries the call released to make room
 * @param uniqueRetainedPixels unique live native pixels retained when the call finished
 * @param reservedPixels native pixels the call reserved from the hard peak
 */
data class TransitionRenderStats(
    val reusedTiles: Int,
    val upgradedTiles: Int,
    val renderedTiles: Int,
    val evictedTiles: Int,
    val uniqueRetainedPixels: Long,
    val reservedPixels: Long,
)
