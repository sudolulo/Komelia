package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import snd.komelia.image.KomeliaImage
import snd.komelia.image.ReaderImage
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PanelsReaderStateTest {
    @Test
    fun `first painter readiness completes when image reports terminal error`() = runTest {
        val image = TestReaderImage()
        val readiness = async { awaitFirstPainter(image) }
        runCurrent()

        assertFalse(readiness.isCompleted)

        image.error.value = IllegalStateException("painter failed")
        runCurrent()

        assertTrue(readiness.isCompleted)
        readiness.await()
    }

    private class TestReaderImage : ReaderImage {
        override val pageId = ReaderImage.PageId("book", 1)
        override val originalSize = MutableStateFlow<IntSize?>(null)
        override val displaySize = MutableStateFlow<IntSize?>(null)
        override val currentSize = MutableStateFlow<IntSize?>(null)
        override val painter = MutableStateFlow<Painter?>(null)
        override val error = MutableStateFlow<Throwable?>(null)

        override fun requestUpdate(
            maxDisplaySize: IntSize,
            zoomFactor: Float,
            visibleDisplaySize: IntRect,
        ) = Unit

        override suspend fun getOriginalImageSize(): Result<IntSize> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getOriginalImage(): Result<KomeliaImage> =
            Result.failure(UnsupportedOperationException())

        override fun close() = Unit
    }
}
