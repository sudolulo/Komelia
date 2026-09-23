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

/**
 * Outcome of preparing the render coverage a panel transition needs.
 *
 * The renderer answers with the widest coverage it can hold inside the hard peak. Correctness
 * comes first: every non-[Rejected] answer is complete for the movement it permits, so no branch
 * can expose a blank viewport.
 */
sealed interface PreparedTransitionCoverage {
    /**
     * The full animated coverage is installed; the caller may animate the movement.
     *
     * [stats] is null when the renderer does not account for native pixels at all. Zero is a
     * meaningful value here, so a missing payload must never be reported as a zero one.
     */
    data class Animated(
        val lease: PreparedUpdateLease,
        val stats: TransitionRenderStats? = null,
    ) : PreparedTransitionCoverage

    /**
     * Only the two endpoints could be admitted. Both are independently complete, so the caller
     * may jump straight to the destination, but it must not animate across the untiled path.
     *
     * [stats] carries the same meaning as on [Animated].
     */
    data class EndpointOnly(
        val lease: PreparedUpdateLease,
        val stats: TransitionRenderStats? = null,
    ) : PreparedTransitionCoverage

    /**
     * Not even the endpoints fit the hard peak. Nothing was rendered and nothing was published,
     * so the caller must keep its current frame and transform.
     */
    data object Rejected : PreparedTransitionCoverage
}
