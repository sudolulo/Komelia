package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class PanelTransitionController(
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {
    private val mutablePhase = MutableStateFlow(PanelTransitionPhase.Idle)
    private var transitionJob: Job? = null
    private var transitionIdentity: Any? = null

    val isActive: Boolean
        get() = transitionJob?.isActive == true

    val phase: StateFlow<PanelTransitionPhase> = mutablePhase.asStateFlow()

    fun launch(
        prepare: suspend () -> Unit,
        isStillValid: () -> Boolean,
        commit: suspend () -> Unit,
    ): Boolean {
        if (transitionJob?.isActive == true) return false

        val identity = Any()
        transitionIdentity = identity
        mutablePhase.value = PanelTransitionPhase.Preparing

        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                prepare()
                if (!isStillValid()) return@launch
                currentCoroutineContext().ensureActive()

                mutablePhase.value = PanelTransitionPhase.Animating
                commit()
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                onError(error)
            } finally {
                if (transitionIdentity === identity) {
                    transitionIdentity = null
                    transitionJob = null
                    mutablePhase.value = PanelTransitionPhase.Idle
                }
            }
        }
        transitionJob = job
        job.start()
        return true
    }

    fun cancel() {
        transitionJob?.cancel()
    }
}

internal suspend fun PanelTransitionController.onViewportChanged(
    updateViewport: suspend () -> Unit,
) {
    when (phase.value) {
        PanelTransitionPhase.Animating -> Unit
        PanelTransitionPhase.Preparing -> {
            cancel()
            updateViewport()
        }

        PanelTransitionPhase.Idle -> updateViewport()
    }
}

internal enum class PanelTransitionPhase {
    Idle,
    Preparing,
    Animating,
}

internal data class PreparedPanelTransition(
    val sourcePage: PanelsReaderState.PanelsPage,
    val sourceIndex: PanelsReaderState.PageIndex,
    val areaSize: IntSize,
    val targetSize: IntSize,
    val targetPanelIndex: Int,
    val targetZoom: Float,
    val targetOffset: Offset,
    val corridor: IntRect,
)
