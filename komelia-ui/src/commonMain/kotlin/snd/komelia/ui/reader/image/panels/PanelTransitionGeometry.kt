package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import snd.komelia.image.ViewportRenderRequirement
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
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

/**
 * What a panel transition has to be able to draw once it has arrived.
 *
 * A transition moves on the already-retained fit-zoom page frame, which covers the whole page, so
 * nothing along the swept path has to be described here. Only the viewport the movement ends on
 * is rendered, and only after the movement.
 */
internal data class PanelTransitionRequirements(
    /** the view the transition ends on, at the target zoom */
    val destination: ViewportRenderRequirement,
    val targetTransformation: Transformation,
)

/**
 * Derives the render requirements of a panel transition from its destination.
 *
 * All regions are expressed in fitted display coordinates and clipped to [targetSize].
 */
internal fun panelTransitionRequirements(
    targetSize: IntSize,
    areaSize: IntSize,
    targetTransformation: Transformation,
): PanelTransitionRequirements {
    val destinationRegion = visibleDisplayRegion(targetSize, areaSize, targetTransformation)

    return PanelTransitionRequirements(
        destination = ViewportRenderRequirement(destinationRegion, targetTransformation.scale),
        targetTransformation = targetTransformation,
    )
}
