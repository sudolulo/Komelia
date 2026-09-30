package snd.komelia.image

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Surface
import snd.komelia.image.processing.ImageProcessingPipeline
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Measurement harness for the retained page-frame layer. Not a test: it asserts nothing about
 * product behaviour, it prints numbers.
 *
 * It is excluded from the ordinary suite by komelia-domain/core/build.gradle.kts, which drops
 * any class matching PageFrameRetentionMeasurement* unless the komeliaMeasurement Gradle
 * property is set. Run it with exactly:
 *
 *   ./gradlew :komelia-domain:core:jvmTest -PkomeliaMeasurement \
 *       --tests 'snd.komelia.image.PageFrameRetentionMeasurement' --rerun-tasks --console=plain
 *
 * WHAT IS REAL AND WHAT IS NOT
 *  - real: every line of TilingReaderImage - doUpdate, doFullResize, doTile, generationFor,
 *    retainPageFrame/publishPageFrame/isMaterialDisplayChange, TileHandle refcounting, the
 *    arbiter's single processing lane. Render counts, output pixel counts and the liveness of
 *    every native image are therefore the renderer's own behaviour, not a model of it.
 *  - real: the raster work. resizeImage and getImageRegion below allocate a Skia raster surface
 *    of the requested destination size and resample the page into it, exactly the shape of
 *    DesktopReaderImage's vips-resize-then-Image.makeFromBitmap. The resampler is Skia's rather
 *    than libvips': no desktop libvips build exists in this checkout, so the production decoder
 *    cannot be loaded here. Absolute milliseconds are therefore Skia-resampler milliseconds;
 *    the A-vs-B ratio is what carries over.
 *  - modelled: the caller. komelia-domain/core cannot see komelia-ui, so the reader's call
 *    sequence (page-load fit update, transition prepare, post-arrival viewport update) and its
 *    geometry math are reproduced here from PanelsReaderState/ScreenScaleState. The formulas are
 *    copied verbatim and marked; the renderer-facing calls are the production ones.
 *  - modelled: the reader issues its page-load and post-arrival viewport updates through
 *    requestUpdate, which is conflated and fire-and-forget. Here they go through prepareUpdate,
 *    which is the same doUpdate path with a completion signal, so a phase can be attributed.
 */
private const val measurementRepetitions = 5
private const val measurementWarmups = 1

/** the measured device page from TilingReaderImagePreparationTest */
private val measurementPageSize = IntSize(2_588, 4_000)

/** max zoom of ScreenScaleState.zoomLimits */
private const val maxZoom = 5f

private const val bytesPerPixel = 4L

/**
 * A comic page split into four panels, in source pixels. Two half-width panels on top, then two
 * full-width bands, which is what gives a transition sequence a mix of zoom levels.
 */
private val measurementPanels = listOf(
    IntRect(60, 60, 1_280, 1_180),
    IntRect(1_308, 60, 2_528, 1_180),
    IntRect(60, 1_240, 2_528, 2_500),
    IntRect(60, 2_560, 2_528, 3_940),
)

private enum class Variant {
    /** today: a transition republishes the retained fit-zoom page frame */
    RETAINED,

    /** proposed: no retention, so every transition takes the existing fit-zoom fallback */
    NO_RETENTION,
}

private enum class RenderKind { FULL_RESIZE, TILE }

class PageFrameRetentionMeasurement {
    @Test
    fun `page frame retention cost`() {
        val source = SourcePage(measurementPageSize)
        try {
            val geometries = listOf(
                MeasuredGeometry(
                    name = "phone 1080x2400",
                    area = IntSize(1_080, 2_400),
                    rotatedArea = IntSize(2_400, 1_080),
                ),
                MeasuredGeometry(
                    // the documented fitted display of 1192x1843 is what this area produces;
                    // a 1080x2400 area cannot produce it, see the report
                    name = "phone fitted 1192x1843",
                    area = IntSize(1_192, 1_843),
                    rotatedArea = IntSize(1_843, 1_192),
                ),
                MeasuredGeometry(
                    name = "tablet 2560x1440",
                    area = IntSize(2_560, 1_440),
                    rotatedArea = IntSize(1_440, 2_560),
                ),
            )

            val report = StringBuilder()
            report.appendLine()
            report.appendLine("=== retained page frame: A (retention) vs B (no retention) ===")
            report.appendLine(
                "page ${measurementPageSize.width}x${measurementPageSize.height}, " +
                    "$measurementWarmups warmup + $measurementRepetitions timed repetitions per cell"
            )

            for (geometry in geometries) {
                for (variant in Variant.entries) {
                    val runs = mutableListOf<SessionResult>()
                    repeat(measurementWarmups + measurementRepetitions) { index ->
                        val result = runBlocking(Dispatchers.Default) { runSession(source, geometry, variant) }
                        if (index >= measurementWarmups) runs += result
                    }
                    report.appendLine()
                    report.appendLine(renderCell(geometry, variant, runs))
                }
            }
            println(report)
        } finally {
            source.close()
        }
    }
}

private class MeasuredGeometry(
    val name: String,
    val area: IntSize,
    val rotatedArea: IntSize,
)

// ---------------------------------------------------------------------------------------------
// session script
// ---------------------------------------------------------------------------------------------

/**
 * One realistic reading session: five distinct pages, four panel transitions on each, a device
 * rotation part way through and a revisit of a page that is still in the reader's page cache
 * (PageLoadCoordinator holds ten, so a revisit reuses the very same ReaderImage instance).
 */
private suspend fun runSession(
    source: SourcePage,
    geometry: MeasuredGeometry,
    variant: Variant,
): SessionResult {
    val accountant = SessionAccountant()
    val pages = mutableMapOf<Int, MeasuringReaderImage>()
    val transitions = mutableListOf<TransitionSample>()
    var pageOpenRenders = 0
    var pageOpenPixels = 0L
    var area = geometry.area

    // pageIndex, and whether this visit is a revisit of a cached page
    val visitOrder = listOf(0, 1, 2, 3, 4, 2)

    try {
        for ((visit, pageIndex) in visitOrder.withIndex()) {
            // the rotation lands between the third and the fourth page visit
            if (visit == 3) area = geometry.rotatedArea

            val image = pages.getOrPut(pageIndex) {
                MeasuringReaderImage(source, accountant, ReaderImage.PageId("measurement", pageIndex))
            }
            val view = viewFor(image, area)

            // page load: getScaleFor sets fit zoom, then updateImageState issues the viewport
            // update for it. This is the render that creates the page frame variant A reuses.
            val beforeOpen = image.snapshot()
            image.prepareUpdate(area, listOf(view.fitRequirement())).close()
            image.quiesce(area, listOf(view.fitRequirement()))
            val afterOpen = image.snapshot()
            pageOpenRenders += afterOpen.renders - beforeOpen.renders
            pageOpenPixels += afterOpen.pixels - beforeOpen.pixels

            // enterFirstPanel, then nextPanel three times
            for (panelIndex in measurementPanels.indices) {
                transitions += runTransition(
                    image = image,
                    accountant = accountant,
                    view = view,
                    variant = variant,
                    panelIndex = panelIndex,
                    label = "visit$visit page$pageIndex panel$panelIndex",
                )
            }

            if (visit == 2) {
                // the rotation itself: prepareMaterialResize runs the page-load fit geometry,
                // its viewport update and then re-enters the current panel as a transition
                val rotatedView = viewFor(image, geometry.rotatedArea)
                val beforeResize = image.snapshot()
                image.prepareUpdate(
                    geometry.rotatedArea,
                    listOf(rotatedView.fitRequirement()),
                ).close()
                image.quiesce(geometry.rotatedArea, listOf(rotatedView.fitRequirement()))
                val afterResize = image.snapshot()
                pageOpenRenders += afterResize.renders - beforeResize.renders
                pageOpenPixels += afterResize.pixels - beforeResize.pixels

                transitions += runTransition(
                    image = image,
                    accountant = accountant,
                    view = rotatedView,
                    variant = variant,
                    panelIndex = measurementPanels.lastIndex,
                    label = "rotation re-entry",
                )
            }
        }

        return SessionResult(
            transitions = transitions.toList(),
            pageOpenRenders = pageOpenRenders,
            pageOpenPixels = pageOpenPixels,
            steadyLiveBytes = accountant.liveBytes(),
            steadyPageFrameBytes = accountant.liveFullResizeBytes(),
            peakLiveBytes = accountant.peakBytes(),
        )
    } finally {
        pages.values.forEach { it.close() }
    }
}

/**
 * One panel transition, as PanelsReaderState.launchPanelTransition runs it: prepare (republish
 * the page frame, or render the whole page at fit zoom when none is available), then movement,
 * then the post-arrival viewport update at panel zoom that afterRelease issues.
 *
 * Variant B makes publishPageFrame unavailable by not consulting it, which is the only way to
 * reach the production fallback without editing production code. The retention itself still
 * runs inside the renderer; see liveFullResizeBytes for how that is accounted for.
 */
private suspend fun runTransition(
    image: MeasuringReaderImage,
    accountant: SessionAccountant,
    view: ViewGeometry,
    variant: Variant,
    panelIndex: Int,
    label: String,
): TransitionSample {
    val panel = view.panelTransform(measurementPanels[panelIndex])
    val beforePrepare = image.snapshot()

    val start = TimeSource.Monotonic.markNow()
    val pageFrame = when (variant) {
        Variant.RETAINED -> image.publishPageFrame()
        Variant.NO_RETENTION -> null
    }
    val republished = pageFrame != null
    val lease = pageFrame ?: image.prepareUpdate(view.area, listOf(view.fitRequirement()))
    val readinessNanos = start.elapsedNow().inWholeNanoseconds

    val afterPrepare = image.snapshot()
    lease.close()

    // afterRelease: updateImageState(force = true) at the arrival transformation
    val arrivalRequirement = ViewportRenderRequirement(
        region = visibleDisplayRegion(view.target, view.area, panel.offset, panel.scale),
        zoomFactor = panel.scale,
    )
    image.prepareUpdate(view.area, listOf(arrivalRequirement)).close()
    image.quiesce(view.area, listOf(arrivalRequirement))
    val afterArrival = image.snapshot()

    return TransitionSample(
        label = label,
        republished = republished,
        prepareRenders = afterPrepare.renders - beforePrepare.renders,
        preparePixels = afterPrepare.pixels - beforePrepare.pixels,
        arrivalRenders = afterArrival.renders - afterPrepare.renders,
        arrivalPixels = afterArrival.pixels - afterPrepare.pixels,
        readinessNanos = readinessNanos,
        steadyLiveBytes = accountant.liveBytes(),
        steadyPageFrameBytes = accountant.liveFullResizeBytes(),
        panelZoom = panel.scale,
    )
}

private class TransitionSample(
    val label: String,
    val republished: Boolean,
    val prepareRenders: Int,
    val preparePixels: Long,
    val arrivalRenders: Int,
    val arrivalPixels: Long,
    val readinessNanos: Long,
    val steadyLiveBytes: Long,
    val steadyPageFrameBytes: Long,
    val panelZoom: Float,
) {
    val renders get() = prepareRenders + arrivalRenders
    val pixels get() = preparePixels + arrivalPixels
}

private class SessionResult(
    val transitions: List<TransitionSample>,
    val pageOpenRenders: Int,
    val pageOpenPixels: Long,
    val steadyLiveBytes: Long,
    val steadyPageFrameBytes: Long,
    val peakLiveBytes: Long,
) {
    val transitionRenders get() = transitions.sumOf { it.renders }
    val transitionPixels get() = transitions.sumOf { it.pixels }
    val prepareRenders get() = transitions.sumOf { it.prepareRenders }
    val preparePixels get() = transitions.sumOf { it.preparePixels }
    val republished get() = transitions.count { it.republished }
    val readinessMillis get() = transitions.map { it.readinessNanos / 1_000_000.0 }
}

// ---------------------------------------------------------------------------------------------
// geometry, copied from PanelsReaderState / ScreenScaleState / PanelTransitionGeometry
// ---------------------------------------------------------------------------------------------

private suspend fun viewFor(image: ReaderImage, area: IntSize): ViewGeometry {
    // getScaleFor: calculateSizeForArea(containerSize, allowUpscale = true)
    val target = checkNotNull(image.calculateSizeForArea(area, true))
    return ViewGeometry(area = area, target = target, page = measurementPageSize)
}

private class ViewGeometry(
    val area: IntSize,
    val target: IntSize,
    val page: IntSize,
) {
    /** ScreenScaleState.scaleFor100PercentZoom */
    private val scaleFor100PercentZoom =
        max(area.width.toFloat() / target.width, area.height.toFloat() / target.height)

    /** ScreenScaleState.scaleForFullVisibility; setZoom(0f) clamps to it, so it is the fit scale */
    val fitScale = min(area.width.toFloat() / target.width, area.height.toFloat() / target.height)

    private val minZoom = fitScale / scaleFor100PercentZoom

    /** PanelsReaderState.fitZoomPageRequirement */
    fun fitRequirement() = ViewportRenderRequirement(
        region = IntRect(0, 0, target.width, target.height),
        zoomFactor = fitScale,
    )

    /** PanelsReaderState.getPanelOffsetAndZoom, then ScreenScaleState.setZoom/applyLimits */
    fun panelTransform(panel: IntRect): PanelTransform {
        val xScale = target.width.toFloat() / page.width
        val yScale = target.height.toFloat() / page.height

        val bboxLeft = panel.left.coerceAtLeast(0) * xScale
        val bboxRight = panel.right.coerceAtMost(page.width) * xScale
        val bboxBottom = panel.bottom.coerceAtMost(page.height) * yScale
        val bboxTop = panel.top.coerceAtLeast(0) * yScale
        val bboxWidth = bboxRight - bboxLeft
        val bboxHeight = bboxBottom - bboxTop

        val scale = min(area.width / bboxWidth, area.height / bboxHeight)
        val zoom = (scale / scaleFor100PercentZoom).coerceIn(minZoom, maxZoom)
        val appliedScale = zoom * scaleFor100PercentZoom

        val centerX = (bboxLeft - target.width / 2.0f) * -1.0f
        val centerY = (bboxTop - target.height / 2.0f) * -1.0f
        val rawOffset = Offset(
            (centerX - bboxWidth / 2.0f) * scale,
            (centerY - bboxHeight / 2.0f) * scale,
        )

        // ScreenScaleState.applyLimits with overscroll off
        val xLimit = offsetLimit(target.width * appliedScale, area.width.toFloat())
        val yLimit = offsetLimit(target.height * appliedScale, area.height.toFloat())
        return PanelTransform(
            offset = Offset(
                rawOffset.x.coerceIn(-xLimit, xLimit),
                rawOffset.y.coerceIn(-yLimit, yLimit),
            ),
            scale = appliedScale,
        )
    }

    private fun offsetLimit(targetSize: Float, areaSize: Float) =
        (targetSize / 2 - areaSize / 2).coerceAtLeast(0f)
}

private class PanelTransform(val offset: Offset, val scale: Float)

/** PanelTransitionGeometry.visibleDisplayRegion */
private fun visibleDisplayRegion(
    displaySize: IntSize,
    areaSize: IntSize,
    offset: Offset,
    scale: Float,
): IntRect {
    require(scale > 0f)
    val visibleHeight = (displaySize.height * scale - areaSize.height) / 2f
    val visibleWidth = (displaySize.width * scale - areaSize.width) / 2f
    val top = ((visibleHeight - offset.y) / scale).roundToInt().coerceIn(0..displaySize.height)
    val left = ((visibleWidth - offset.x) / scale).roundToInt().coerceIn(0..displaySize.width)
    return IntRect(
        top = top,
        left = left,
        bottom = (top + areaSize.height / scale).roundToInt().coerceIn(0..displaySize.height),
        right = (left + areaSize.width / scale).roundToInt().coerceIn(0..displaySize.width),
    )
}

// ---------------------------------------------------------------------------------------------
// the renderer under measurement
// ---------------------------------------------------------------------------------------------

/**
 * Session-wide native pixel accounting. Every page of a session shares one, so peak is the peak
 * of the whole session's live set over time rather than a sum of per-page peaks taken at
 * different moments.
 */
private class SessionAccountant {
    private val lock = Any()
    private var live = 0L
    private var liveFullResize = 0L
    private var peak = 0L

    fun allocated(kind: RenderKind, bytes: Long) = synchronized(lock) {
        live += bytes
        if (kind == RenderKind.FULL_RESIZE) liveFullResize += bytes
        if (live > peak) peak = live
    }

    fun released(kind: RenderKind, bytes: Long) = synchronized(lock) {
        live -= bytes
        if (kind == RenderKind.FULL_RESIZE) liveFullResize -= bytes
    }

    fun liveBytes(): Long = synchronized(lock) { live }
    fun liveFullResizeBytes(): Long = synchronized(lock) { liveFullResize }
    fun peakBytes(): Long = synchronized(lock) { peak }
}

private class MeasuringReaderImage(
    private val source: SourcePage,
    private val accountant: SessionAccountant,
    pageId: ReaderImage.PageId,
) : TilingReaderImage(
    imageSource = ImageSource.MemorySource(byteArrayOf(0)),
    imageDecoder = SourcePageDecoder(source.komeliaImage),
    processingPipeline = ImageProcessingPipeline(),
    stretchImages = MutableStateFlow(false),
    upsamplingMode = MutableStateFlow(UpsamplingMode.BILINEAR),
    downSamplingKernel = MutableStateFlow(ReduceKernel.DEFAULT),
    linearLightDownSampling = MutableStateFlow(false),
    pageId = pageId,
) {
    private val renderCount = AtomicInteger()
    private val renderedPixels = AtomicLong()
    private val allocations = CopyOnWriteArrayList<Allocation>()

    fun snapshot() = Counters(renderCount.get(), renderedPixels.get())

    /**
     * Waits for the renderer to settle. Resubmitting the requirements the current frame already
     * satisfies costs no render and cannot start until every earlier demand - including the
     * lease teardown that releases retired ownership - has left the arbiter's single lane.
     */
    suspend fun quiesce(area: IntSize, requirements: List<ViewportRenderRequirement>) {
        prepareUpdate(area, requirements).close()
        // the barrier's own lease teardown is the only thing that can still be in flight, and
        // it releases references rather than taking them
        withContext(Dispatchers.Default) { delay(2) }
    }

    override fun closeRenderImage(renderImage: RenderImage) {
        val allocation = allocations.firstOrNull { it.image === renderImage }
        if (allocation != null && allocation.markClosed()) {
            accountant.released(allocation.kind, allocation.bytes)
        }
        runCatching { renderImage.close() }
    }

    override fun createTilePainter(
        tiles: List<ReaderImageTile>,
        displaySize: IntSize,
        scaleFactor: Double,
    ): TiledPainter = NoDrawPainter

    override suspend fun resizeImage(
        image: KomeliaImage,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        val rendered = rasterize(
            src = Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            width = scaleWidth,
            height = scaleHeight,
            kind = RenderKind.FULL_RESIZE,
        )
        return ReaderImageData(
            scaleWidth,
            scaleHeight,
            IntSize(scaleWidth, scaleHeight),
            listOf(rendered),
            null,
        )
    }

    override suspend fun getImageRegion(
        image: KomeliaImage,
        imageRegion: IntRect,
        scaleWidth: Int,
        scaleHeight: Int,
    ): ReaderImageData {
        val rendered = rasterize(
            src = Rect.makeLTRB(
                imageRegion.left.toFloat(),
                imageRegion.top.toFloat(),
                imageRegion.right.toFloat(),
                imageRegion.bottom.toFloat(),
            ),
            width = scaleWidth,
            height = scaleHeight,
            kind = RenderKind.TILE,
        )
        return ReaderImageData(
            scaleWidth,
            scaleHeight,
            IntSize(scaleWidth, scaleHeight),
            listOf(rendered),
            null,
        )
    }

    /**
     * A real resample of [src] out of the page into a freshly allocated raster of [width] x
     * [height], which is the work DesktopReaderImage pays through vips resize plus
     * Image.makeFromBitmap.
     */
    private fun rasterize(src: Rect, width: Int, height: Int, kind: RenderKind): Image {
        val surface = Surface.makeRasterN32Premul(width, height)
        try {
            surface.canvas.drawImageRect(
                image = source.image,
                src = src,
                dst = Rect.makeWH(width.toFloat(), height.toFloat()),
                samplingMode = SamplingMode.LINEAR,
                paint = null,
                strict = true,
            )
            val rendered = surface.makeImageSnapshot()
            val bytes = width.toLong() * height.toLong() * bytesPerPixel
            renderCount.incrementAndGet()
            renderedPixels.addAndGet(width.toLong() * height.toLong())
            allocations += Allocation(rendered, kind, bytes)
            accountant.allocated(kind, bytes)
            return rendered
        } finally {
            surface.close()
        }
    }

    private class Allocation(val image: Image, val kind: RenderKind, val bytes: Long) {
        private val closed = AtomicInteger()
        fun markClosed() = closed.compareAndSet(0, 1)
    }
}

private class Counters(val renders: Int, val pixels: Long)

private object NoDrawPainter : TilingReaderImage.TiledPainter() {
    override val intrinsicSize = Size.Zero
    override fun DrawScope.onDraw() = Unit
    override fun withSamplingMode(upsamplingMode: UpsamplingMode): TilingReaderImage.TiledPainter = this
}

/** One decoded page, shared by every page instance of a session. */
private class SourcePage(size: IntSize) : AutoCloseable {
    val width = size.width
    val height = size.height
    val image: Image = renderPageContent(size)
    val komeliaImage: KomeliaImage = SharedKomeliaImage(size)

    override fun close() {
        image.close()
    }
}

/** Content the resampler cannot shortcut: a few hundred filled rectangles over the page. */
private fun renderPageContent(size: IntSize): Image {
    val surface = Surface.makeRasterN32Premul(size.width, size.height)
    try {
        val random = Random(4_242)
        val paint = Paint()
        repeat(400) {
            val left = random.nextInt(size.width).toFloat()
            val top = random.nextInt(size.height).toFloat()
            paint.color = random.nextInt().or(0xFF000000.toInt())
            surface.canvas.drawRect(
                Rect.makeLTRB(
                    left,
                    top,
                    (left + random.nextInt(1, 400)).coerceAtMost(size.width.toFloat()),
                    (top + random.nextInt(1, 400)).coerceAtMost(size.height.toFloat()),
                ),
                paint,
            )
        }
        return surface.makeImageSnapshot()
    } finally {
        surface.close()
    }
}

/**
 * The decoded page as the renderer sees it. Only its dimensions are read: every raster operation
 * goes through MeasuringReaderImage's overrides. It is shared, so closing it is a no-op and the
 * harness frees the underlying Skia image once.
 */
private class SharedKomeliaImage(private val size: IntSize) : KomeliaImage {
    override val width = size.width
    override val height = size.height
    override val bands = 4
    override val type = ImageFormat.RGBA_8888
    override val pagesLoaded = 1
    override val pagesTotal = 1
    override val pageHeight = size.height
    override val pageDelays: IntArray? = null

    override suspend fun extractArea(rect: ImageRect): KomeliaImage = this
    override suspend fun resize(scaleWidth: Int, scaleHeight: Int, linear: Boolean, kernel: ReduceKernel) = this
    override suspend fun shrink(factor: Double): KomeliaImage = this
    override suspend fun findTrim() = ImageRect(0, 0, size.width, size.height)
    override suspend fun makeHistogram(): KomeliaImage = this
    override suspend fun mapLookupTable(table: ByteArray): KomeliaImage = this
    override suspend fun getBytes() = ByteArray(0)
    override fun close() = Unit
}

private class SourcePageDecoder(private val image: KomeliaImage) : KomeliaImageDecoder {
    override suspend fun decode(encoded: ByteArray, nPages: Int?): KomeliaImage = image
    override suspend fun decodeFromFile(path: String, nPages: Int?): KomeliaImage = image
    override suspend fun decodeAndResize(
        encoded: ByteArray,
        scaleWidth: Int,
        scaleHeight: Int,
        crop: Boolean,
        nPages: Int?,
    ): KomeliaImage = image

    override suspend fun decodeAndResize(
        path: String,
        scaleWidth: Int,
        scaleHeight: Int,
        crop: Boolean,
        nPages: Int?,
    ): KomeliaImage = image
}

// ---------------------------------------------------------------------------------------------
// reporting
// ---------------------------------------------------------------------------------------------

private fun renderCell(
    geometry: MeasuredGeometry,
    variant: Variant,
    runs: List<SessionResult>,
): String {
    val first = runs.first()
    val transitionCount = first.transitions.size
    val out = StringBuilder()
    out.appendLine("--- ${geometry.name} / ${variant.name} ---")
    out.appendLine(
        "area ${geometry.area.width}x${geometry.area.height} -> rotated " +
            "${geometry.rotatedArea.width}x${geometry.rotatedArea.height}; " +
            "$transitionCount transitions, ${first.republished} served by a republished page frame"
    )
    out.appendLine(
        "panel zooms: " + first.transitions.map { "%.2f".format(it.panelZoom) }.distinct().joinToString(", ")
    )

    out.appendLine(
        spread("native renders / transition", runs.map { it.transitionRenders.toDouble() / transitionCount }, "%.2f")
    )
    out.appendLine(spread("native renders, transitions total", runs.map { it.transitionRenders.toDouble() }, "%.1f"))
    out.appendLine(spread("  of which prepare phase", runs.map { it.prepareRenders.toDouble() }, "%.1f"))
    out.appendLine(spread("native renders, page loads", runs.map { it.pageOpenRenders.toDouble() }, "%.1f"))

    out.appendLine(
        spread(
            "Mpx / transition",
            runs.map { it.transitionPixels.toDouble() / transitionCount / 1e6 },
            "%.2f",
        )
    )
    out.appendLine(spread("Mpx transitions total", runs.map { it.transitionPixels / 1e6 }, "%.1f"))
    out.appendLine(spread("  of which prepare phase", runs.map { it.preparePixels / 1e6 }, "%.1f"))
    out.appendLine(spread("Mpx page loads", runs.map { it.pageOpenPixels / 1e6 }, "%.1f"))
    out.appendLine(
        spread(
            "MiB session total (px x 4)",
            runs.map { (it.transitionPixels + it.pageOpenPixels) * bytesPerPixel / 1_048_576.0 },
            "%.1f",
        )
    )

    out.appendLine(spread("readiness mean ms", runs.map { mean(it.readinessMillis) }, "%.2f"))
    out.appendLine(spread("readiness median ms", runs.map { percentile(it.readinessMillis, 50.0) }, "%.2f"))
    out.appendLine(spread("readiness p95 ms", runs.map { percentile(it.readinessMillis, 95.0) }, "%.2f"))
    out.appendLine(spread("readiness max ms", runs.map { it.readinessMillis.max() }, "%.2f"))

    out.appendLine(spread("steady live MiB", runs.map { it.steadyLiveBytes / 1_048_576.0 }, "%.2f"))
    out.appendLine(
        spread(
            "  of which retained page frames MiB",
            runs.map { it.steadyPageFrameBytes / 1_048_576.0 },
            "%.2f",
        )
    )
    out.appendLine(spread("peak live MiB (session-wide)", runs.map { it.peakLiveBytes / 1_048_576.0 }, "%.2f"))
    out.appendLine(
        spread(
            "steady live MiB minus page frames",
            runs.map { (it.steadyLiveBytes - it.steadyPageFrameBytes) / 1_048_576.0 },
            "%.2f",
        )
    )
    return out.toString().trimEnd()
}

private fun spread(label: String, values: List<Double>, format: String): String {
    val m = mean(values)
    val lo = values.min()
    val hi = values.max()
    val sd = if (values.size < 2) 0.0 else {
        kotlin.math.sqrt(values.sumOf { (it - m) * (it - m) } / (values.size - 1))
    }
    return "  %-36s mean $format  sd $format  range [$format, $format]".format(label, m, sd, lo, hi)
}

private fun mean(values: List<Double>) = values.sum() / values.size

private fun percentile(values: List<Double>, p: Double): Double {
    val sorted = values.sorted()
    if (sorted.size == 1) return sorted.first()
    val rank = (p / 100.0) * (sorted.size - 1)
    val low = rank.toInt()
    val high = min(low + 1, sorted.size - 1)
    val weight = rank - low
    return sorted[low] * (1 - weight) + sorted[high] * weight
}
