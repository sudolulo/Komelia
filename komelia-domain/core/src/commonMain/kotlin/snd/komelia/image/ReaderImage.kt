package snd.komelia.image

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

fun interface PreparedUpdateLease : AutoCloseable {
    override fun close()
}

interface ReaderImage : AutoCloseable {
    val pageId: PageId

    val originalSize: StateFlow<IntSize?>
    val displaySize: StateFlow<IntSize?>
    val currentSize: StateFlow<IntSize?>
    val painter: StateFlow<Painter?>
    val error: StateFlow<Throwable?>

    /**
     * request an asynchronous update.
     * Should drop requests if unable to process them fast enough while only processing the most recent request
     *
     * @param maxDisplaySize - area available for image display (e.g. screen size 1920x1080 for full screen display)
     * @param zoomFactor - factor used to calculate max display size and target dimensions of the image.
     * (1920x1080 maxDisplaySize with scale factor of 1.5 will turn into 2880x1620 max size)
     * @param visibleDisplaySize - visible viewport region,
     * this enables optimizations for partial loading and will only load visible portion of the image
     */
    fun requestUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    )

    /**
     * Prepare an exact set of viewport requirements without conflation and return only after
     * a painter covering all of them is installed, or the existing painter already covers them.
     * The returned lease keeps later viewport updates from publishing until it is closed.
     *
     * Every requirement contributes its own density, so a high zoom viewport is never
     * replaced by under-resolved tiles because a lower zoom viewport overlaps it.
     */
    suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    ): PreparedUpdateLease

    /**
     * Prepare the coverage a panel transition needs, degrading instead of failing when the
     * hard peak cannot hold all of it.
     *
     * The renderer publishes [animatedRequirements] when they fit, otherwise retries with
     * [endpointRequirements], otherwise rejects the transition before rendering anything.
     *
     * The default implementation always animates: a renderer without a native pixel budget has
     * nothing to degrade to. [TilingReaderImage] overrides it with real admission.
     *
     * It also reports no [TransitionRenderStats]: a renderer with no native pixel budget has
     * nothing to account for, and reporting zeroes would read as "did no work" instead.
     */
    suspend fun prepareTransitionUpdate(
        maxDisplaySize: IntSize,
        animatedRequirements: List<ViewportRenderRequirement>,
        endpointRequirements: List<ViewportRenderRequirement>,
    ): PreparedTransitionCoverage = PreparedTransitionCoverage.Animated(
        lease = prepareUpdate(maxDisplaySize = maxDisplaySize, requirements = animatedRequirements),
        stats = null,
    )

    /**
     * Populate reusable tile coverage for [requirements] without publishing it.
     *
     * Best effort and bounded: it never replaces the visible painter, never changes the size
     * flows, never acquires a preparation lease, yields the processing lane to demand work at
     * every tile boundary, and stops rather than pushing the retained working set over budget.
     */
    suspend fun prefetchUpdate(
        maxDisplaySize: IntSize,
        requirements: List<ViewportRenderRequirement>,
    )

    /** normal-viewport convenience path over [prepareUpdate] with a single requirement */
    suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ): PreparedUpdateLease = prepareUpdate(
        maxDisplaySize = maxDisplaySize,
        requirements = listOf(ViewportRenderRequirement(visibleDisplaySize, zoomFactor)),
    )

    suspend fun getOriginalImageSize(): Result<IntSize>

    suspend fun getOriginalImage(): Result<KomeliaImage>

    /**
     * calculate image dimensions that can be displayed on maxDisplaySize area
     * scales image up or down while preserving original image aspect ratio
     *
     * @param maxDisplaySize - area available for image display (e.g. screen size 1920x1080 for full screen display)
     * @param allowUpscale - limit max image dimensions to original image size
     */
    suspend fun calculateSizeForArea(maxDisplaySize: IntSize, allowUpscale: Boolean): IntSize? {
        val imageSize = getOriginalImageSize().getOrNull()
            ?: return null
        if (imageSize == IntSize.Zero) return maxDisplaySize
        val widthRatio = maxDisplaySize.width.toDouble() / imageSize.width
        val heightRatio = maxDisplaySize.height.toDouble() / imageSize.height
        val displayScaleFactor = widthRatio.coerceAtMost(heightRatio)

        val displayWidth = (imageSize.width * displayScaleFactor).roundToInt()
        val displayHeight = (imageSize.height * displayScaleFactor).roundToInt()

        val size = if (allowUpscale) IntSize(displayWidth, displayHeight)
        else IntSize(
            displayWidth.coerceAtMost(imageSize.width),
            displayHeight.coerceAtMost(imageSize.height)
        )
        return size
    }

    data class PageId(
        val bookId: String,
        val pageNumber: Int
    ) {
        override fun toString(): String {
            return "${bookId}_$pageNumber"
        }
    }
}
