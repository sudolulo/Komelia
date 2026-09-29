package snd.komelia.ui.reader.image.panels

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import snd.komelia.image.PreparedUpdateLease

internal class PanelTransitionController(
    private val scope: CoroutineScope,
    private val onError: (Throwable) -> Unit,
) {
    private val mutablePhase = MutableStateFlow(PanelTransitionPhase.Idle)
    private var transitionJob: Job? = null
    private var transitionIdentity: Any? = null

    /** the running transition's own validity check, consulted before a viewport change cancels it */
    private var transitionIsStillValid: (() -> Boolean)? = null

    val isActive: Boolean
        get() = transitionJob?.isActive == true

    val phase: StateFlow<PanelTransitionPhase> = mutablePhase.asStateFlow()

    fun launch(
        prepare: suspend () -> PreparedUpdateLease,
        isStillValid: () -> Boolean,
        commit: suspend () -> Unit,
        afterRelease: suspend () -> Unit = {},
    ): Boolean {
        if (transitionJob?.isActive == true) return false

        val identity = Any()
        transitionIdentity = identity
        transitionIsStillValid = isStillValid
        mutablePhase.value = PanelTransitionPhase.Preparing

        val job = scope.launch(start = CoroutineStart.LAZY) {
            var preparedUpdate: PreparedUpdateLease? = null
            var transitionActivated = false
            try {
                preparedUpdate = prepare()
                if (!isStillValid()) return@launch
                currentCoroutineContext().ensureActive()

                mutablePhase.value = PanelTransitionPhase.Animating
                transitionActivated = true
                commit()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                currentCoroutineContext().ensureActive()
                onError(error)
            } finally {
                preparedUpdate?.close()
                if (transitionActivated) {
                    try {
                        withContext(NonCancellable) { afterRelease() }
                    } catch (error: Throwable) {
                        onError(error)
                    }
                }
                if (transitionIdentity === identity) {
                    transitionIdentity = null
                    transitionIsStillValid = null
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

    fun captureViewportChange(): PanelTransitionViewportChange = PanelTransitionViewportChange(
        phase = phase.value,
        transitionIdentity = transitionIdentity,
    )

    suspend fun onViewportChanged(
        change: PanelTransitionViewportChange = captureViewportChange(),
        updateViewport: suspend () -> Unit,
    ) {
        val currentPhase = phase.value
        val isCurrentTransition = change.transitionIdentity === transitionIdentity

        when (change.phase) {
            PanelTransitionPhase.Animating -> Unit
            PanelTransitionPhase.Preparing -> {
                when {
                    isCurrentTransition && currentPhase == PanelTransitionPhase.Preparing -> {
                        // The capture is asynchronous, so a change the transition itself was
                        // started from can arrive here after it entered Preparing. Cancelling on
                        // that would discard a preparation that is still exactly right, so the
                        // transition's own validity decides: only a change that moved the viewport
                        // away from what it prepared for cancels it.
                        if (transitionIsStillValid?.invoke() != true) {
                            cancel()
                            updateViewport()
                        }
                    }

                    transitionIdentity == null && currentPhase == PanelTransitionPhase.Idle -> updateViewport()
                }
            }

            PanelTransitionPhase.Idle -> {
                if (isCurrentTransition && currentPhase == PanelTransitionPhase.Idle) updateViewport()
            }
        }
    }
}

internal data class PanelTransitionViewportChange(
    val phase: PanelTransitionPhase,
    val transitionIdentity: Any?,
)

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
)

/** Where a transition to a single panel ends up. */
internal data class PanelTarget(
    val targetZoom: Float,
    val targetOffset: Offset,
)
