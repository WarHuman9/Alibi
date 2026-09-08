package com.example.alibi.telecom.session

import android.telecom.Call
import com.example.alibi.service.SimulatedConnection
import com.example.alibi.telecom.CallMetadata
import com.example.alibi.telecom.SimulationPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Implementation of [CallSession] for simulated calls.
 */
class SimulatedCallSession(
    private val connection: SimulatedConnection,
    override val id: String
) : CallSession {

    private val _metadata = MutableStateFlow(createInitialMetadata())
    override val metadata: StateFlow<CallMetadata> = _metadata.asStateFlow()

    private fun createInitialMetadata(): CallMetadata {
        val request = connection.request
        return CallMetadata(
            id = id,
            isSimulated = true,
            number = request.phoneNumber,
            name = request.phoneNumber,
            state = if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) 
                Call.STATE_DIALING else Call.STATE_RINGING,
            phase = if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) 
                SimulationPhase.DIALING else SimulationPhase.RINGING,
            type = request.direction,
            startTime = request.startTime ?: System.currentTimeMillis(),
            simHandle = request.simHandle,
            features = request.features
        )
    }

    override fun answer() {
        connection.onAnswer()
    }

    override fun hangup() {
        connection.terminate(userInitiated = true)
    }

    override fun hold() {
        connection.onHold()
    }

    override fun resume() {
        connection.onUnhold()
    }

    /**
     * Internal helper to update metadata from external controllers (like SimulationController).
     */
    fun updateState(state: Int, phase: SimulationPhase? = null) {
        _metadata.update { current ->
            current.copy(
                state = state,
                phase = phase ?: current.phase,
                isHolding = (state == Call.STATE_HOLDING),
                answerTime = if (state == Call.STATE_ACTIVE && current.answerTime == 0L) 
                    System.currentTimeMillis() else current.answerTime
            )
        }
    }
}
