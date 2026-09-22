package snd.komelia.ui.reader.image

import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenScaleStateTest {
    @Test
    fun `scrollTo uses the requested duration`() = runTest {
        val frameClock = TestFrameClock()
        val state = scrollableState(CoroutineScope(coroutineContext + frameClock))

        val animation = state.scrollTo(Offset(50f, 0f), durationMillis = 250)
        runCurrent()
        frameClock.sendFrame(0L)
        runCurrent()
        frameClock.sendFrame(250_000_000L)
        runCurrent()

        assertFalse(animation.isActive)
    }

    @Test
    fun `scrollTo defaults to 1000 milliseconds`() = runTest {
        val frameClock = TestFrameClock()
        val state = scrollableState(CoroutineScope(coroutineContext + frameClock))

        val animation = state.scrollTo(Offset(50f, 0f))
        runCurrent()
        frameClock.sendFrame(0L)
        runCurrent()
        frameClock.sendFrame(250_000_000L)
        runCurrent()
        assertTrue(animation.isActive)

        frameClock.sendFrame(1_000_000_000L)
        runCurrent()
        assertFalse(animation.isActive)
    }

    @Test
    fun `predicted zoom transformation equals setZoom result`() {
        val state = ScreenScaleState().apply {
            setAreaSize(IntSize(1080, 2208))
            setTargetSize(IntSize(1080, 1669).toSize())
        }
        val predicted = state.transformationForZoom(2.4f)

        state.setZoom(2.4f)

        assertEquals(predicted, state.transformation.value)
    }

    private fun scrollableState(scope: CoroutineScope) = ScreenScaleState().apply {
        composeScope = scope
        setAreaSize(IntSize(100, 100))
        setTargetSize(IntSize(100, 100).toSize())
        setZoom(2f)
    }
}

private class TestFrameClock : MonotonicFrameClock {
    private val frames = Channel<Long>(Channel.UNLIMITED)

    suspend fun sendFrame(timeNanos: Long) {
        frames.send(timeNanos)
    }

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R =
        onFrame(frames.receive())
}
