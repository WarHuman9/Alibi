package com.example.alibi.telecom.session

import com.example.alibi.telecom.CallMetadata
import com.example.alibi.telecom.SimulationPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A placeholder session used for UI feedback before the system binds a real or simulated connection.
 */
class OptimisticCallSession(
    initialMetadata: CallMetadata
) : CallSession {
    override val id: String = initialMetadata.id
    private val _metadata = MutableStateFlow(initialMetadata)
    override val metadata: StateFlow<CallMetadata> = _metadata.asStateFlow()

    override fun answer() {}
    override fun hangup() {}
    override fun hold() {}
    override fun resume() {}

    /**
     * Allows the controller to update the optimistic state before the real session is bound.
     */
    fun updateState(state: Int, phase: SimulationPhase? = null) {
        _metadata.update { current ->
            current.copy(
                state = state,
                phase = phase ?: current.phase,
                isHolding = (state == android.telecom.Call.STATE_HOLDING),
                answerTime = if (state == android.telecom.Call.STATE_ACTIVE && current.answerTime == 0L) 
                    System.currentTimeMillis() else current.answerTime
            )
        }
    }
}
