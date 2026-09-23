package snd.komelia.image

/**
 * Outcome of preparing the render coverage a panel transition needs.
 *
 * The renderer answers with the widest coverage it can hold inside the hard peak. Correctness
 * comes first: every non-[Rejected] answer is complete for the movement it permits, so no branch
 * can expose a blank viewport.
 */
sealed interface PreparedTransitionCoverage {
    /** The full animated coverage is installed; the caller may animate the movement. */
    data class Animated(val lease: PreparedUpdateLease) : PreparedTransitionCoverage

    /**
     * Only the two endpoints could be admitted. Both are independently complete, so the caller
     * may jump straight to the destination, but it must not animate across the untiled path.
     */
    data class EndpointOnly(val lease: PreparedUpdateLease) : PreparedTransitionCoverage

    /**
     * Not even the endpoints fit the hard peak. Nothing was rendered and nothing was published,
     * so the caller must keep its current frame and transform.
     */
    data object Rejected : PreparedTransitionCoverage
}
