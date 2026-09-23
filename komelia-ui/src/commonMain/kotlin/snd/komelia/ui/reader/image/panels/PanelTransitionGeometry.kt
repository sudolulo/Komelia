package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import snd.komelia.image.ViewportRenderRequirement
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.math.max
import kotlin.math.roundToInt

internal fun visibleDisplayRegion(
    displaySize: IntSize,
    areaSize: IntSize,
    transformation: Transformation,
): IntRect {
    require(transformation.scale > 0f) { "Transformation scale must be positive" }

    val visibleHeight = (displaySize.height * transformation.scale - areaSize.height) / 2f
    val visibleWidth = (displaySize.width * transformation.scale - areaSize.width) / 2f
    val top = ((visibleHeight - transformation.offset.y) / transformation.scale)
        .roundToInt()
        .coerceIn(0..displaySize.height)
    val left = ((visibleWidth - transformation.offset.x) / transformation.scale)
        .roundToInt()
        .coerceIn(0..displaySize.width)

    return IntRect(
        top = top,
        left = left,
        bottom = (top + areaSize.height / transformation.scale)
            .roundToInt()
            .coerceIn(0..displaySize.height),
        right = (left + areaSize.width / transformation.scale)
            .roundToInt()
            .coerceIn(0..displaySize.width),
    )
}

internal fun transitionCorridor(
    regions: List<IntRect>,
    displaySize: IntSize,
): IntRect {
    val nonEmptyRegions = regions.filter { it.width > 0 && it.height > 0 }
    require(nonEmptyRegions.isNotEmpty()) { "At least one non-empty region is required" }

    return IntRect(
        left = nonEmptyRegions.minOf { it.left }.coerceIn(0..displaySize.width),
        top = nonEmptyRegions.minOf { it.top }.coerceIn(0..displaySize.height),
        right = nonEmptyRegions.maxOf { it.right }.coerceIn(0..displaySize.width),
        bottom = nonEmptyRegions.maxOf { it.bottom }.coerceIn(0..displaySize.height),
    )
}

/**
 * Every viewport a panel transition has to be able to draw, kept as separate requirements so
 * each one carries its own density. Merging them into one rectangle at one zoom would either
 * under-resolve the higher zoom endpoint or render the lower zoom one far denser than it needs.
 */
internal data class PanelTransitionRequirements(
    /** the view the transition starts from, at the zoom it is currently drawn with */
    val source: ViewportRenderRequirement,
    /** the view right after the zoom step, before any movement, at the target zoom */
    val zoomStart: ViewportRenderRequirement,
    /** the view the transition ends on, at the target zoom */
    val destination: ViewportRenderRequirement,
    /** everything the animation sweeps across, at the densest zoom the sweep is drawn with */
    val swept: ViewportRenderRequirement,
    val targetTransformation: Transformation,
) {
    /** complete coverage for an animated transition */
    val animatedRequirements: List<ViewportRenderRequirement> =
        listOf(source, zoomStart, destination, swept)

    /**
     * Coverage for the over-budget fallback: two independently complete endpoints and no swept
     * path, because the fallback jumps instead of animating. Overlapping endpoints stay separate
     * requirements so a shared tile is still rendered at the denser of the two.
     */
    val endpointRequirements: List<ViewportRenderRequirement> = listOf(source, destination)
}

/**
 * Derives the render requirements of a panel transition from its endpoints.
 *
 * All regions are expressed in fitted display coordinates and clipped to [targetSize].
 */
internal fun panelTransitionRequirements(
    targetSize: IntSize,
    areaSize: IntSize,
    sourceTransformation: Transformation,
    targetZoomStartTransformation: Transformation,
    targetTransformation: Transformation,
): PanelTransitionRequirements {
    val sourceRegion = visibleDisplayRegion(targetSize, areaSize, sourceTransformation)
    val zoomStartRegion = visibleDisplayRegion(targetSize, areaSize, targetZoomStartTransformation)
    val destinationRegion = visibleDisplayRegion(targetSize, areaSize, targetTransformation)
    val sweptRegion = transitionCorridor(
        regions = listOf(sourceRegion, zoomStartRegion, destinationRegion),
        displaySize = targetSize,
    )

    val sourceZoom = sourceTransformation.scale
    val targetZoom = targetTransformation.scale

    return PanelTransitionRequirements(
        source = ViewportRenderRequirement(sourceRegion, sourceZoom),
        zoomStart = ViewportRenderRequirement(zoomStartRegion, targetZoom),
        destination = ViewportRenderRequirement(destinationRegion, targetZoom),
        swept = ViewportRenderRequirement(sweptRegion, max(sourceZoom, targetZoom)),
        targetTransformation = targetTransformation,
    )
}
