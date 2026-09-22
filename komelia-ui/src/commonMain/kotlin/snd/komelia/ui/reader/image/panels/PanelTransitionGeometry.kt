package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
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
