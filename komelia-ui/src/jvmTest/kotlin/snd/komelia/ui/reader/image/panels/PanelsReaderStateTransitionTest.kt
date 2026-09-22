package snd.komelia.ui.reader.image.panels

import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import cafe.adriel.voyager.core.annotation.InternalVoyagerApi
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import snd.komelia.AppNotifications
import snd.komelia.color.repository.BookColorCorrectionRepository
import snd.komelia.image.BookImageLoader
import snd.komelia.image.ImageRect
import snd.komelia.image.KomeliaImage
import snd.komelia.image.KomeliaImageDecoder
import snd.komelia.image.KomeliaPanelDetector
import snd.komelia.image.ReaderImage
import snd.komelia.image.ReaderImageFactory
import snd.komelia.image.ReaderImageResult
import snd.komelia.komga.api.KomgaBookApi
import snd.komelia.komga.api.KomgaReadListApi
import snd.komelia.komga.api.KomgaSeriesApi
import snd.komelia.onnxruntime.OnnxRuntimeExecutionProvider
import snd.komelia.onnxruntime.OnnxRuntimeRfDetr
import snd.komelia.settings.ImageReaderSettingsRepository
import snd.komelia.settings.model.PagedReadingDirection
import snd.komelia.ui.BookSiblingsContext
import snd.komelia.ui.reader.image.PageMetadata
import snd.komelia.ui.reader.image.ReaderState
import snd.komelia.ui.reader.image.ScreenScaleState
import snd.komga.client.book.KomgaBookId
import java.lang.reflect.Proxy
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals

class PanelsReaderStateTransitionTest {
    @Test
    fun `real panel transition prepares corridor and suppresses animation viewport updates`() = runBlocking {
        val image = ControlledReaderImage(IntSize(300, 300))
        val frameClock = ControlledFrameClock()
        val animationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + frameClock)
        val screenScaleState = ScreenScaleState().apply {
            composeScope = animationScope
            setAreaSize(IntSize(100, 100))
            setTargetSize(IntSize(100, 100).toSize())
            setZoom(3f)
        }
        val state = panelsReaderState(screenScaleState)
        state.currentPage.value = PanelsReaderState.PanelsPage(
            metadata = PageMetadata(KomgaBookId("book"), 1, IntSize(300, 300)),
            imageResult = ReaderImageResult.Success(image),
            panelData = PanelsReaderState.PanelData(
                panels = listOf(
                    ImageRect(100, 100, 200, 200),
                    ImageRect(200, 200, 300, 300),
                ),
                originalImageSize = IntSize(300, 300),
                panelCoversMajorityOfImage = false,
            ),
        )

        try {
            state.initialize()
            awaitViewportObserver(state, image)
            val startTransformation = screenScaleState.transformation.value

            state.nextPanel()
            val preparation = withTimeout(2_000) { image.preparationStarted.await() }

            assertEquals(IntSize(100, 100), preparation.maxDisplaySize)
            assertEquals(3f, preparation.zoomFactor, 0.00001f)
            assertEquals(IntRect(33, 33, 100, 100), preparation.visibleDisplaySize)
            assertEquals(PanelsReaderState.PageIndex(0, 0, false), state.currentPageIndex.value)
            assertEquals(startTransformation, screenScaleState.transformation.value)

            image.releasePreparation.complete(Unit)
            withTimeout(2_000) {
                while (state.currentPageIndex.value.panel != 1) delay(10)
            }

            repeat(10) { frameClock.sendFrame(it * 16_000_000L) }
            delay(50)
            assertEquals(emptyList(), image.viewportUpdates.toList())

            repeat(60) { frameClock.sendFrame((it + 10) * 16_000_000L) }
            withTimeout(2_000) {
                while (image.viewportUpdates.isEmpty()) delay(10)
            }
            delay(250)

            assertEquals(
                listOf(
                    ImageUpdate(
                        maxDisplaySize = IntSize(100, 100),
                        zoomFactor = preparation.zoomFactor,
                        visibleDisplaySize = IntRect(67, 67, 100, 100),
                    ),
                ),
                image.viewportUpdates.toList(),
            )
        } finally {
            state.stop()
            animationScope.cancel()
        }
    }

    private suspend fun awaitViewportObserver(
        state: PanelsReaderState,
        image: ControlledReaderImage,
    ) {
        withTimeout(2_000) {
            var offset = 1f
            while (image.viewportUpdates.isEmpty()) {
                state.screenScaleState.setOffset(Offset(offset, offset))
                offset = if (offset == 1f) 2f else 1f
                delay(10)
            }
        }
        delay(150)
        image.viewportUpdates.clear()
        state.screenScaleState.setOffset(Offset.Zero)
        withTimeout(2_000) {
            while (image.viewportUpdates.isEmpty()) delay(10)
        }
        delay(150)
        image.viewportUpdates.clear()
    }
}

private class ControlledFrameClock : MonotonicFrameClock {
    private val frames = Channel<Long>(Channel.UNLIMITED)

    suspend fun sendFrame(timeNanos: Long) {
        frames.send(timeNanos)
    }

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        onFrame(frames.receive())
}

private data class ImageUpdate(
    val maxDisplaySize: IntSize,
    val zoomFactor: Float,
    val visibleDisplaySize: IntRect,
)

private class ControlledReaderImage(
    imageSize: IntSize,
) : ReaderImage {
    val preparationStarted = CompletableDeferred<ImageUpdate>()
    val releasePreparation = CompletableDeferred<Unit>()
    val viewportUpdates = Collections.synchronizedList(mutableListOf<ImageUpdate>())

    override val pageId = ReaderImage.PageId("book", 1)
    override val originalSize = MutableStateFlow<IntSize?>(imageSize)
    override val displaySize = MutableStateFlow<IntSize?>(imageSize)
    override val currentSize = MutableStateFlow<IntSize?>(imageSize)
    override val painter = MutableStateFlow<Painter?>(null)
    override val error = MutableStateFlow<Throwable?>(null)

    override fun requestUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ) {
        viewportUpdates += ImageUpdate(maxDisplaySize, zoomFactor, visibleDisplaySize)
    }

    override suspend fun prepareUpdate(
        maxDisplaySize: IntSize,
        zoomFactor: Float,
        visibleDisplaySize: IntRect,
    ) {
        preparationStarted.complete(ImageUpdate(maxDisplaySize, zoomFactor, visibleDisplaySize))
        releasePreparation.await()
    }

    override suspend fun getOriginalImageSize(): Result<IntSize> =
        Result.success(checkNotNull(originalSize.value))

    override suspend fun getOriginalImage(): Result<KomeliaImage> =
        Result.failure(UnsupportedOperationException())

    override fun close() = Unit
}

@OptIn(InternalVoyagerApi::class)
private fun CoroutineScope.panelsReaderState(screenScaleState: ScreenScaleState): PanelsReaderState {
    val bookApi = unsupportedProxy<KomgaBookApi>()
    val settingsRepository = settingsRepository()
    val pageChangeFlow = MutableSharedFlow<Unit>()
    val readerState = ReaderState(
        bookApi = bookApi,
        seriesApi = unsupportedProxy<KomgaSeriesApi>(),
        readListApi = unsupportedProxy<KomgaReadListApi>(),
        navigator = Navigator(
            screens = listOf(unsupportedProxy<Screen>()),
            key = "panel-transition-test",
            stateHolder = unsupportedProxy<SaveableStateHolder>(),
            disposeBehavior = NavigatorDisposeBehavior(),
            parent = null,
        ),
        appNotifications = AppNotifications(),
        readerSettingsRepository = settingsRepository,
        currentBookId = MutableStateFlow(null),
        markReadProgress = false,
        stateScope = this,
        bookSiblingsContext = BookSiblingsContext.Series,
        colorCorrectionRepository = unsupportedProxy<BookColorCorrectionRepository>(),
        pageChangeFlow = pageChangeFlow,
    )
    val imageLoader = BookImageLoader(
        bookClient = MutableStateFlow(bookApi),
        imageDecoder = unsupportedProxy<KomeliaImageDecoder>(),
        readerImageFactory = unsupportedProxy<ReaderImageFactory>(),
        diskCache = null,
    )
    val panelDetector = object : KomeliaPanelDetector(
        ortRfDetr = unsupportedProxy<OnnxRuntimeRfDetr>(),
        executionProvider = OnnxRuntimeExecutionProvider.CPU,
        deviceId = MutableStateFlow(0),
        updateFlow = emptyFlow(),
    ) {
        override fun getModelPath(): String? = null
    }

    return PanelsReaderState(
        cleanupScope = this,
        settingsRepository = settingsRepository,
        appNotifications = AppNotifications(),
        readerState = readerState,
        imageLoader = imageLoader,
        pageChangeFlow = pageChangeFlow,
        onnxRuntimeRfDetr = panelDetector,
        screenScaleState = screenScaleState,
    )
}

private fun settingsRepository(): ImageReaderSettingsRepository =
    Proxy.newProxyInstance(
        ImageReaderSettingsRepository::class.java.classLoader,
        arrayOf(ImageReaderSettingsRepository::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getPagedReaderReadingDirection" -> flowOf(PagedReadingDirection.LEFT_TO_RIGHT)
            "toString" -> "PanelTransitionSettingsRepository"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as ImageReaderSettingsRepository

private inline fun <reified T : Any> unsupportedProxy(): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, arguments ->
        when (method.name) {
            "equals" -> proxy === arguments?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "Unsupported${T::class.simpleName}"
            else -> throw UnsupportedOperationException(method.name)
        }
    } as T
