package snd.komelia.image

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toRect
import kotlin.math.roundToInt

/**
 * A single viewport that a render request must cover.
 *
 * @param region visible viewport region in fitted display coordinates (zoom factor 1)
 * @param zoomFactor zoom applied to the fitted display geometry for this viewport
 */
data class ViewportRenderRequirement(
    val region: IntRect,
    val zoomFactor: Float,
) {
    init {
        require(zoomFactor.isFinite()) { "zoom factor must be finite, got $zoomFactor" }
        require(zoomFactor > 0f) { "zoom factor must be positive, got $zoomFactor" }
        require(region.width > 0 && region.height > 0) { "viewport region must not be empty, got $region" }
    }

    /**
     * Region that must hold rendered tiles for this viewport, including the
     * off-screen margin that keeps small movements from exposing empty tiles.
     */
    internal fun visibilityWindow(displayArea: IntSize): Rect =
        visibilityWindowFor(region.toRect(), displayArea)
}

/**
 * Maximum rendered pixel size a single source tile must have to satisfy every
 * requirement that its display region overlaps.
 *
 * Returns null when no requirement covers the tile, meaning the tile needs no render at all.
 */
internal fun requiredTileRenderSize(
    tileSourceBounds: IntRect,
    tileDisplayRegion: Rect,
    displayScaleFactor: Double,
    displayArea: IntSize,
    requirements: List<ViewportRenderRequirement>,
): IntSize? {
    require(requirements.isNotEmpty()) { "at least one viewport requirement is required" }
    require(displayScaleFactor.isFinite() && displayScaleFactor > 0.0) {
        "display scale factor must be finite and positive, got $displayScaleFactor"
    }

    var width = 0
    var height = 0
    for (requirement in requirements) {
        if (!requirement.visibilityWindow(displayArea).overlaps(tileDisplayRegion)) continue

        val scaleFactor = displayScaleFactor * requirement.zoomFactor
        width = maxOf(width, checkedRenderDimension(tileSourceBounds.width, scaleFactor))
        height = maxOf(height, checkedRenderDimension(tileSourceBounds.height, scaleFactor))
    }

    if (width == 0 || height == 0) return null
    return IntSize(width, height)
}

/**
 * Rounds a source extent to the exact output extent handed to the native renderer.
 */
internal fun checkedRenderDimension(sourceExtent: Int, scaleFactor: Double): Int {
    require(sourceExtent > 0) { "source extent must be positive, got $sourceExtent" }
    require(scaleFactor.isFinite() && scaleFactor > 0.0) {
        "scale factor must be finite and positive, got $scaleFactor"
    }

    val scaled = sourceExtent * scaleFactor
    require(scaled <= Int.MAX_VALUE.toDouble()) {
        "output extent $scaled exceeds the supported range"
    }
    // a non-empty source region always occupies at least one output pixel
    return scaled.roundToInt().coerceAtLeast(1)
}
