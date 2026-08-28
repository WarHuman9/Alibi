package com.example.alibi.telecom

import android.provider.CallLog
import android.telecom.Call
import android.telecom.PhoneAccountHandle

/**
 * Pure data registry for call state across the application.
 */
enum class SimulationPhase {
    IDLE,
    DIALING,
    RINGING,
    SIMULATED_ACTIVE,
    HOLDING,
    DISCONNECTED
}

/**
 * Atomic snapshot of a call's state.
 */
data class CallMetadata(
    val id: String,
    val call: Call? = null,
    val isSimulated: Boolean = false,
    val number: String = "Unknown",
    val name: String = "Unknown",
    val state: Int = Call.STATE_DISCONNECTED,
    val phase: SimulationPhase = SimulationPhase.IDLE,
    val startTime: Long = 0,
    val answerTime: Long = 0,
    val isHolding: Boolean = false,
    val isRealCall: Boolean = false,
    val type: Int = CallLog.Calls.INCOMING_TYPE,
    val simHandle: PhoneAccountHandle? = null,
    val features: Int = 0
)

/**
 * Immutable snapshot of a call's metadata for logging.
 */
data class CallLogSnapshot(
    val number: String,
    val type: Int,
    val startTime: Long,
    val answerTime: Long,
    val endTime: Long = System.currentTimeMillis(),
    val simHandle: PhoneAccountHandle? = null,
    val features: Int = 0,
    val isSimulated: Boolean = false
)

/**
 * Unified state for the Call Manager.
 */
data class CallManagerState(
    val activeCalls: Map<String, CallMetadata> = emptyMap(),
    val pendingMetadata: Map<String, SimulatedCallRequest> = emptyMap(),
    val currentCallId: String? = null,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isHolding: Boolean = false
)

/**
 * Actions that can be performed on a call, observed by simulation controllers.
 */
enum class CallAction {
    ANSWER,
    HANGUP,
    HOLD,
    RESUME
}
