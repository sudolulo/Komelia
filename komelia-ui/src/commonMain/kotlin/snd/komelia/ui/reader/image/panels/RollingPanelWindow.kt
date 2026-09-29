package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import snd.komelia.image.ImageRect
import snd.komelia.image.ViewportRenderRequirement
import snd.komelia.ui.reader.image.ScreenScaleState.Transformation
import kotlin.math.max
import kotlin.math.min

/**
 * Exact previous/current/next panel viewports at each panel's own panel zoom, with no overscan.
 * Clips at the first and last panel. Returns an empty list when geometry is not usable yet.
 */
internal fun rollingPanelRequirements(
    panels: List<ImageRect>,
    currentPanelIndex: Int,
    areaSize: IntSize,
    targetSize: IntSize,
    transformationForZoom: (Float) -> Transformation,
    imageSize: IntSize,
): List<ViewportRenderRequirement> {
    if (areaSize.width <= 0 || areaSize.height <= 0) return emptyList()
    if (targetSize.width <= 0 || targetSize.height <= 0) return emptyList()
    if (panels.isEmpty()) return emptyList()

    val indices = listOf(currentPanelIndex - 1, currentPanelIndex, currentPanelIndex + 1)
        .filter { it in panels.indices }

    return indices.mapNotNull { index ->
        val panel = panels[index]
        val (offset, zoom) = getPanelOffsetAndZoom(
            imageSize = imageSize,
            areaSize = areaSize,
            targetSize = targetSize,
            panel = panel,
        )
        if (!zoom.isFinite() || zoom <= 0f) return@mapNotNull null
        val scale = transformationForZoom(zoom).scale
        if (!scale.isFinite() || scale <= 0f) return@mapNotNull null

        val region = visibleDisplayRegion(
            displaySize = targetSize,
            areaSize = areaSize,
            transformation = Transformation(offset = offset, scale = scale),
        )
        if (region.width <= 0 || region.height <= 0) return@mapNotNull null

        ViewportRenderRequirement(region = region, zoomFactor = scale, overscan = false)
    }
}

/** The whole fitted page at [fitScale], or null while its geometry is unusable. */
internal fun fitPageRequirement(
    targetSize: IntSize,
    fitScale: Float,
): ViewportRenderRequirement? {
    if (targetSize.width <= 0 || targetSize.height <= 0) return null
    if (!fitScale.isFinite() || fitScale <= 0f) return null
    return ViewportRenderRequirement(
        region = IntRect(0, 0, targetSize.width, targetSize.height),
        zoomFactor = fitScale,
    )
}

internal fun getPanelOffsetAndZoom(
    imageSize: IntSize,
    areaSize: IntSize,
    targetSize: IntSize,
    panel: ImageRect,
): Pair<Offset, Float> {
    val xScale = targetSize.width.toFloat() / imageSize.width
    val yScale = targetSize.height.toFloat() / imageSize.height

    val bboxLeft = panel.left.coerceAtLeast(0) * xScale
    val bboxRight = panel.right.coerceAtMost(imageSize.width) * xScale
    val bboxBottom = panel.bottom.coerceAtMost(imageSize.height) * yScale
    val bboxTop = panel.top.coerceAtLeast(0) * yScale
    val bboxWidth = bboxRight - bboxLeft
    val bboxHeight = bboxBottom - bboxTop

    val scale = min(
        areaSize.width / bboxWidth,
        areaSize.height / bboxHeight,
    )
    val fitToScreenScale = max(
        areaSize.width.toFloat() / targetSize.width,
        areaSize.height.toFloat() / targetSize.height,
    )
    val zoom = scale / fitToScreenScale

    val bboxHalfWidth = bboxWidth / 2f
    val bboxHalfHeight = bboxHeight / 2f
    val imageHalfWidth = targetSize.width / 2f
    val imageHalfHeight = targetSize.height / 2f

    val centerX = (bboxLeft - imageHalfWidth) * -1f
    val centerY = (bboxTop - imageHalfHeight) * -1f
    val offset = Offset(
        (centerX - bboxHalfWidth) * scale,
        (centerY - bboxHalfHeight) * scale,
    )

    return offset to zoom
}
