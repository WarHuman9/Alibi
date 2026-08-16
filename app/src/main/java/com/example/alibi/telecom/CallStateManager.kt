package com.example.alibi.telecom

import android.content.Context
import android.provider.CallLog
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import android.content.Intent
import android.os.Build
import com.example.alibi.service.CallNotificationService
import com.example.alibi.service.SimulatedConnection
import com.example.alibi.util.CallLogHelper

/**
 * Singleton manager for call state across the application.
 * Synchronizes Real and Simulated call states for the UI.
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
 * Task 10: Renamed from CallInfo to CallMetadata.
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
 * Task 10: Renamed from CallMetadata to CallLogSnapshot.
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
 * Task 10: Consolidation of separate flows.
 */
data class CallManagerState(
    val activeCalls: Map<String, CallMetadata> = emptyMap(),
    val pendingMetadata: Map<String, SimulatedCallRequest> = emptyMap(),
    val currentCallId: String? = null,
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = false,
    val isHolding: Boolean = false
)

object CallStateManager {
    private const val TAG = "CallStateManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val activeConnections = mutableMapOf<String, SimulatedConnection>()

    private val _state = MutableStateFlow(CallManagerState())
    val state: StateFlow<CallManagerState> = _state.asStateFlow()

    // Derived properties calculated from unified state
    val activeCalls: StateFlow<Map<String, CallMetadata>> = _state
        .map { it.activeCalls }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val totalActiveCalls: StateFlow<Int> = _state
        .map { it.activeCalls.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val isSimulatedCallActive: StateFlow<Boolean> = _state
        .map { s -> s.activeCalls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isSimulationActive: StateFlow<Boolean> = _state
        .map { s ->
            s.activeCalls.values.any { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentCall: StateFlow<Call?> = _state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.call 
                ?: s.activeCalls.values.find { it.state == Call.STATE_ACTIVE }?.call 
                ?: s.activeCalls.values.lastOrNull()?.call 
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val callState: StateFlow<Int> = _state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.state 
                ?: s.activeCalls.values.find { it.state == Call.STATE_ACTIVE }?.state 
                ?: s.activeCalls.values.lastOrNull()?.state 
                ?: Call.STATE_DISCONNECTED 
        }
        .stateIn(scope, SharingStarted.Eagerly, Call.STATE_DISCONNECTED)

    val simulationPhase: StateFlow<SimulationPhase> = _state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.phase 
                ?: s.activeCalls.values.find { it.phase != SimulationPhase.IDLE }?.phase 
                ?: SimulationPhase.IDLE 
        }
        .stateIn(scope, SharingStarted.Eagerly, SimulationPhase.IDLE)

    val isRealCall: StateFlow<Boolean> = _state
        .map { s -> s.activeCalls.values.any { it.isRealCall } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isHolding: StateFlow<Boolean> = _state
        .map { it.isHolding }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isBusy: StateFlow<Boolean> = _state
        .map { it.activeCalls.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val busyMessage: StateFlow<String?> = _state
        .map { s ->
            when {
                s.activeCalls.values.any { it.isRealCall } -> "A real call is currently happening. Try again later."
                s.activeCalls.values.any { it.isSimulated } -> "There is already an ongoing Simulated call. Try again later."
                else -> null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val answerTime: StateFlow<Long> = _state
        .map { s -> s.activeCalls[s.currentCallId]?.answerTime ?: s.activeCalls.values.lastOrNull()?.answerTime ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val logCallType: StateFlow<Int> = _state
        .map { s -> s.activeCalls[s.currentCallId]?.type ?: s.activeCalls.values.lastOrNull()?.type ?: CallLog.Calls.INCOMING_TYPE }
        .stateIn(scope, SharingStarted.Eagerly, CallLog.Calls.INCOMING_TYPE)

    val isMuted: StateFlow<Boolean> = _state.map { it.isMuted }.stateIn(scope, SharingStarted.Eagerly, false)
    val isSpeakerOn: StateFlow<Boolean> = _state.map { it.isSpeakerOn }.stateIn(scope, SharingStarted.Eagerly, false)

    // Configuration state (transient, used for next simulated call)
    private var nextCallStartTime: Long? = null
    private var nextCallDuration: Long? = null
    private var nextCallSimHandle: PhoneAccountHandle? = null
    private var nextCallFeatures: Int = 0

    val isCloaking: Boolean
        get() {
            val active = _state.value.activeCalls.values.find { it.phase != SimulationPhase.IDLE }
            return (active?.phase == SimulationPhase.DIALING || active?.phase == SimulationPhase.RINGING) &&
                    (active.state == Call.STATE_ACTIVE)
        }

    /**
     * Captures the current call state into an immutable snapshot.
     */
    fun captureSnapshot(id: String? = null): CallLogSnapshot? {
        val currentState = _state.value
        val targetId = id ?: currentState.currentCallId ?: currentState.activeCalls.keys.lastOrNull() ?: return null
        val info = currentState.activeCalls[targetId] ?: return null
        return CallLogSnapshot(
            number = info.number,
            type = info.type,
            startTime = if (info.startTime > 0) info.startTime else System.currentTimeMillis(),
            answerTime = info.answerTime,
            simHandle = info.simHandle,
            features = info.features,
            isSimulated = info.isSimulated
        )
    }

    // Request callbacks for UI -> Service interaction
    var onAnswerRequested: (() -> Unit)? = null
    var onDisconnectRequested: (() -> Unit)? = null
    var onCallStateChangedHook: ((Call, Int) -> Unit)? = null
    private var _onMuteRequested: ((Boolean) -> Unit)? = null
    private var _onSpeakerRequested: ((Boolean) -> Unit)? = null

    var onMuteRequested: ((Boolean) -> Unit)?
        get() = _onMuteRequested
        set(value) { _onMuteRequested = value }

    var onSpeakerRequested: ((Boolean) -> Unit)?
        get() = _onSpeakerRequested
        set(value) { _onSpeakerRequested = value }

    private var isAudioHandlerPriority = false

    fun setAudioHandlers(
        mute: (Boolean) -> Unit,
        speaker: (Boolean) -> Unit,
        priority: Boolean = false
    ) {
        if (priority || !isAudioHandlerPriority || _onMuteRequested == null) {
            Log.d(TAG, "setAudioHandlers: Updating handlers. Priority=$priority")
            _onMuteRequested = mute
            _onSpeakerRequested = speaker
            isAudioHandlerPriority = priority
        } else {
            Log.d(TAG, "setAudioHandlers: Ignoring non-priority update as priority handler is already set.")
        }
    }

    fun clearAudioHandlers(priority: Boolean) {
        if (priority == isAudioHandlerPriority) {
            Log.d(TAG, "clearAudioHandlers: Clearing handlers for priority=$priority")
            _onMuteRequested = null
            _onSpeakerRequested = null
            isAudioHandlerPriority = false
        }
    }

    private val isUserTerminated = AtomicBoolean(false)


    fun updateCallState(id: String, state: Int) {
        val isReal = _state.value.activeCalls[id]?.isRealCall == true
        Log.d(TelecomConstants.STATE_TAG, "updateCallState: id=$id, state=${callStateToString(state)}, isReal=$isReal")
        
        _state.update { s ->
            s.activeCalls[id]?.let { info ->
                var newPhase = info.phase
                var newAnswerTime = info.answerTime

                val isCloaked = isCloaked(info, state)
                
                if (!isCloaked) {
                    newPhase = when (state) {
                        Call.STATE_RINGING -> SimulationPhase.RINGING
                        Call.STATE_DIALING, Call.STATE_CONNECTING -> SimulationPhase.DIALING
                        Call.STATE_ACTIVE -> {
                            if (newAnswerTime == 0L) newAnswerTime = System.currentTimeMillis()
                            SimulationPhase.SIMULATED_ACTIVE
                        }
                        Call.STATE_HOLDING -> SimulationPhase.HOLDING
                        Call.STATE_DISCONNECTED -> SimulationPhase.DISCONNECTED
                        else -> info.phase
                    }
                }

                val newIsHolding = (state == Call.STATE_HOLDING)
                if (state == Call.STATE_ACTIVE && newAnswerTime == 0L) {
                    newAnswerTime = System.currentTimeMillis()
                }

                val updatedInfo = info.copy(
                    state = state,
                    phase = newPhase,
                    isHolding = newIsHolding,
                    answerTime = newAnswerTime
                )
                
                val newActiveCalls = s.activeCalls + (id to updatedInfo)
                val newIsHoldingGlobal = newActiveCalls.values.any { it.isHolding }
                
                s.copy(
                    activeCalls = newActiveCalls,
                    isHolding = newIsHoldingGlobal,
                    currentCallId = if (state == Call.STATE_ACTIVE) id else s.currentCallId
                )
            } ?: s
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val extras = call.details.extras ?: android.os.Bundle.EMPTY
            val alibiId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
            val connectionId = extras.getString(TelecomConstants.EXTRA_CONNECTION_ID)
            val id = alibiId ?: connectionId ?: call.hashCode().toString()
            updateCallState(id, state)
            onCallStateChangedHook?.invoke(call, state)
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            val extras = details.extras ?: android.os.Bundle.EMPTY
            val alibiId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
            if (alibiId != null) {
                val oldId = call.hashCode().toString()
                if (!_state.value.activeCalls.containsKey(alibiId)) {
                    Log.i(TAG, "Alibi ID arrived in details. Re-keying $oldId -> $alibiId")
                    rekeyCall(oldId, alibiId)
                }
            }
        }
    }

    fun addCall(id: String, info: CallMetadata) {
        Log.d(TelecomConstants.STATE_TAG, "addCall: id=$id, isSimulated=${info.isSimulated}")
        _state.update { s ->
            val existing = s.activeCalls[id]
            val updatedInfo = if (existing != null) {
                Log.d(TAG, "Merging system call object into optimistic entry for $id")
                existing.copy(
                    call = info.call,
                    isRealCall = info.isRealCall,
                    number = if (info.number != "Unknown") info.number else existing.number,
                    state = if (info.state != Call.STATE_DISCONNECTED) info.state else existing.state,
                    startTime = if (info.startTime != 0L) info.startTime else existing.startTime
                )
            } else {
                info
            }
            
            val newActiveCalls = s.activeCalls + (id to updatedInfo)
            s.copy(
                activeCalls = newActiveCalls,
                currentCallId = if (updatedInfo.state == Call.STATE_ACTIVE || s.currentCallId == null) id else s.currentCallId
            )
        }
    }

    /**
     * Task 15: Re-key logic to synchronize UI (hashcode) with Simulation (UUID).
     */
    fun rekeyCall(oldId: String, newId: String) {
        Log.d(TelecomConstants.STATE_TAG, "rekeyCall: mapping $oldId -> $newId")
        _state.update { s ->
            val info = s.activeCalls[oldId] ?: return@update s
            val newActiveCalls = s.activeCalls.toMutableMap()
            newActiveCalls.remove(oldId)
            newActiveCalls[newId] = info.copy(id = newId)
            
            val newPendingMetadata = s.pendingMetadata.toMutableMap()
            newPendingMetadata.remove(oldId)?.let { request ->
                newPendingMetadata[newId] = request
            }
            
            s.copy(
                activeCalls = newActiveCalls,
                pendingMetadata = newPendingMetadata,
                currentCallId = if (s.currentCallId == oldId) newId else s.currentCallId
            )
        }
    }

    fun removeCall(id: String) {
        Log.d(TelecomConstants.STATE_TAG, "removeCall: Atomic removal for id=$id")
        _state.update { s ->
            if (!s.activeCalls.containsKey(id)) return@update s
            val newActiveCalls = s.activeCalls.toMutableMap()
            newActiveCalls.remove(id)
            
            val newPendingMetadata = s.pendingMetadata.toMutableMap()
            newPendingMetadata.remove(id)
            
            s.copy(
                activeCalls = newActiveCalls,
                pendingMetadata = newPendingMetadata,
                currentCallId = if (s.currentCallId == id) newActiveCalls.keys.lastOrNull() else s.currentCallId
            )
        }
    }

    /**
     * Emergency reset for the call manager.
     */
    fun clearAllCalls() {
        Log.w(TAG, "Emergency reset: Clearing all active calls and metadata.")
        _state.value = CallManagerState()
        activeConnections.clear()
    }


    fun setCustomStartTime(timestamp: Long?) { nextCallStartTime = timestamp }
    fun setIntendedDuration(duration: Long?) { nextCallDuration = duration }
    fun setMimicSimHandle(handle: PhoneAccountHandle?) { nextCallSimHandle = handle }
    fun setCallFeatures(features: Int) { nextCallFeatures = features }


    fun onCallAdded(call: Call, isSimulated: Boolean? = null, handler: android.os.Handler? = null) {
        val extras = call.details.extras ?: android.os.Bundle.EMPTY
        val alibiId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        val connectionId = extras.getString(TelecomConstants.EXTRA_CONNECTION_ID)
        val id = alibiId ?: connectionId ?: call.hashCode().toString()
        
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        
        val hasAlibiId = call.details.extras?.containsKey(TelecomConstants.EXTRA_ALIBI_CALL_ID) ?: false
        val isSimulatedCall = isSimulated ?: hasAlibiId
        val isRealCall = !isSimulatedCall
    
        val name = call.details.callerDisplayName ?: call.details.handle?.schemeSpecificPart ?: "Unknown"

        // Task 6: Real call preemption
        if (isRealCall && isSimulatedCallActive.value) {
            Log.w(TAG, "Real call detected. Preempting active simulation.")
            val currentConnections = activeConnections.values.toList()
            currentConnections.forEach { it.onDisconnect() }
        }
    
        val initialPhase = when (state) {
            Call.STATE_RINGING -> SimulationPhase.RINGING
            Call.STATE_DIALING, Call.STATE_CONNECTING -> SimulationPhase.DIALING
            Call.STATE_ACTIVE -> SimulationPhase.SIMULATED_ACTIVE
            Call.STATE_HOLDING -> SimulationPhase.HOLDING
            else -> SimulationPhase.IDLE
        }

        var info = CallMetadata(
            id = id,
            call = call,
            isSimulated = isSimulatedCall,
            isRealCall = isRealCall,
            number = call.details.handle?.schemeSpecificPart ?: "Unknown",
            name = name,
            state = state,
            phase = initialPhase,
            startTime = nextCallStartTime ?: System.currentTimeMillis(),
            answerTime = if (state == Call.STATE_ACTIVE) System.currentTimeMillis() else 0L,
            simHandle = nextCallSimHandle,
            features = nextCallFeatures,
            isHolding = (state == Call.STATE_HOLDING)
        )
        
        // Apply any pending metadata
        _state.update { s ->
            val request = s.pendingMetadata[id]
            if (request != null) {
                Log.d(TAG, "Applying pending metadata (SimulatedCallRequest) for call: $id")
                info = info.copy(
                    number = request.phoneNumber,
                    name = request.phoneNumber, // Fallback to number for name
                    startTime = request.startTime ?: info.startTime,
                    simHandle = request.simHandle ?: info.simHandle,
                    features = request.features
                )
                val newPending = s.pendingMetadata.toMutableMap()
                newPending.remove(id)
                s.copy(pendingMetadata = newPending)
            } else {
                s
            }
        }
        
        addCall(id, info)
        if (handler != null) {
            call.registerCallback(callCallback, handler)
        } else {
            call.registerCallback(callCallback)
        }
    }

    fun onCallRemoved(call: Call) {
        val extras = call.details.extras ?: android.os.Bundle.EMPTY
        val alibiId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        val connectionId = extras.getString(TelecomConstants.EXTRA_CONNECTION_ID)
        val id = alibiId ?: connectionId ?: call.hashCode().toString()
        Log.d(TAG, "onCallRemoved: cleaning up call state for $id")
        call.unregisterCallback(callCallback)
        removeCall(id)
    }

    fun setSimulatedCallActive(active: Boolean, phoneNumber: String? = null, state: Int = Call.STATE_ACTIVE, type: Int? = null, id: String? = null) {
        val targetId = id ?: activeConnections.keys.lastOrNull() ?: return
        
        if (active) {
            _state.update { s ->
                val existing = s.activeCalls[targetId]
                if (existing != null) {
                    var newAnswerTime = existing.answerTime
                    val newPhase = when (state) {
                        Call.STATE_RINGING -> SimulationPhase.RINGING
                        Call.STATE_DIALING -> SimulationPhase.DIALING
                        Call.STATE_ACTIVE -> {
                            if (newAnswerTime == 0L) newAnswerTime = System.currentTimeMillis()
                            SimulationPhase.SIMULATED_ACTIVE
                        }
                        Call.STATE_HOLDING -> SimulationPhase.HOLDING
                        else -> existing.phase
                    }
                    val updated = existing.copy(
                        isSimulated = true,
                        number = phoneNumber ?: existing.number,
                        name = phoneNumber ?: existing.name,
                        state = state,
                        phase = newPhase,
                        answerTime = newAnswerTime,
                        isHolding = (state == Call.STATE_HOLDING),
                        type = type ?: existing.type
                    )
                    s.copy(activeCalls = s.activeCalls + (targetId to updated))
                } else {
                    Log.d(TAG, "Call $targetId not found yet. Queueing metadata and Injecting optimistic call.")
                    val request = SimulatedCallRequest(
                        phoneNumber = phoneNumber ?: "Unknown",
                        direction = type ?: CallLog.Calls.INCOMING_TYPE,
                        alibiId = targetId
                    )
                    val optimistic = CallMetadata(
                        id = targetId,
                        isSimulated = true,
                        number = phoneNumber ?: "Unknown",
                        name = phoneNumber ?: "Unknown",
                        state = state,
                        phase = when (state) {
                            Call.STATE_RINGING -> SimulationPhase.RINGING
                            Call.STATE_DIALING -> SimulationPhase.DIALING
                            Call.STATE_ACTIVE -> SimulationPhase.SIMULATED_ACTIVE
                            else -> SimulationPhase.IDLE
                        }
                    )
                    s.copy(
                        pendingMetadata = s.pendingMetadata + (targetId to request),
                        activeCalls = s.activeCalls + (targetId to optimistic)
                    )
                }
            }
        } else {
            removeCall(targetId)
        }
    }


    // --- Termination & Logging ---

    suspend fun terminateSimulatedSession(
        context: Context,
        snapshot: CallLogSnapshot,
        intendedDuration: Long? = null
    ) {
        Log.d(TAG, "Atomic termination started for ${snapshot.number}")
        recordCallEndInternal(context, snapshot, intendedDuration)
    }


    /**
     * Records the end of a call and logs it using a captured snapshot.
     */
    private suspend fun recordCallEndInternal(
        context: Context,
        snapshot: CallLogSnapshot,
        intendedDuration: Long?
    ) = withContext(Dispatchers.IO + NonCancellable) {
        Log.d(TAG, "recordCallEndInternal: Processing log for ${snapshot.number}")

        var finalType = snapshot.type
        var finalDuration = 0L

        when {
            snapshot.type == CallLog.Calls.MISSED_TYPE -> {
                finalDuration = 0L
            }
            (snapshot.type == CallLog.Calls.INCOMING_TYPE) && (snapshot.answerTime == 0L) -> {
                finalType = if (isUserTerminated.get()) CallLog.Calls.REJECTED_TYPE else CallLog.Calls.MISSED_TYPE
                finalDuration = 0L
            }
            snapshot.answerTime > 0L -> {
                val actualElapsed = (snapshot.endTime - snapshot.answerTime) / 1000
                // For Task 6: Use actual elapsed time if it's less than intended (e.g. preemption)
                finalDuration = if (isUserTerminated.get()) {
                    actualElapsed
                } else if (intendedDuration != null) {
                    kotlin.math.min(actualElapsed, intendedDuration)
                } else {
                    actualElapsed
                }
            }
            snapshot.type == CallLog.Calls.OUTGOING_TYPE && snapshot.answerTime == 0L -> {
                finalDuration = 0L
            }
        }

        val helper = CallLogHelper.getInstance(context)
        helper.insertCallLog(snapshot.number, finalDuration, snapshot.startTime, finalType, snapshot.simHandle, snapshot.features)
        Log.d(TAG, "recordCallEndInternal: Insertion requested for ${snapshot.number} (duration=$finalDuration)")
    }

    // --- UI Actions ---

    fun isCurrentCallSimulated(): Boolean {
        return _state.value.activeCalls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED }
    }

    fun answer() {
        currentCall.value?.answer(0)
        onAnswerRequested?.invoke()
    }


    fun disconnect() {
        isUserTerminated.set(true)
        currentCall.value?.disconnect()
        onDisconnectRequested?.invoke()
    }


    fun updateAudioState(muted: Boolean, speaker: Boolean) {
        // Task 18: Guard hardware safety. Real calls often update this via system callback.
        val currentState = _state.value
        val isReal = currentState.activeCalls.values.any { it.isRealCall }
        if (isReal) {
            Log.d("Alibi_RealAudio", "Real Audio Transition: muted=$muted, speaker=$speaker")
            _state.update { it.copy(isMuted = muted, isSpeakerOn = speaker) }
            return 
        }

        Log.d(TelecomConstants.STATE_TAG, "updateAudioState: muted=$muted, speaker=$speaker")
        _state.update { it.copy(isMuted = muted, isSpeakerOn = speaker) }
    }


    fun setIsHolding(holding: Boolean, id: String? = null) {
        val targetId = id ?: activeConnections.keys.lastOrNull() ?: return
        _state.update { s ->
            s.activeCalls[targetId]?.let { info ->
                val newActiveCalls = s.activeCalls + (targetId to info.copy(isHolding = holding))
                s.copy(
                    activeCalls = newActiveCalls,
                    isHolding = newActiveCalls.values.any { it.isHolding }
                )
            } ?: s
        }
    }


    fun registerConnection(id: String, connection: SimulatedConnection) {
        Log.d(TAG, "Registering connection: $id")
        activeConnections[id] = connection
        
        // Task 15: Direct Injection - Check for pending metadata
        _state.update { s ->
            val request = s.pendingMetadata[id]
            if (request != null) {
                Log.d(TAG, "Found pending metadata for $id during registration. Updating/Creating optimistic entry.")
                val info = s.activeCalls[id] ?: CallMetadata(id = id, isSimulated = true)
                val updatedInfo = info.copy(
                    number = request.phoneNumber,
                    name = request.phoneNumber,
                    startTime = request.startTime ?: info.startTime,
                    simHandle = request.simHandle ?: info.simHandle,
                    features = request.features
                )
                val newPending = s.pendingMetadata.toMutableMap()
                newPending.remove(id)
                s.copy(
                    activeCalls = s.activeCalls + (id to updatedInfo),
                    pendingMetadata = newPending
                )
            } else {
                s
            }
        }
    }

    fun unregisterConnection(id: String, context: Context) {
        Log.d(TAG, "Unregistering connection: $id")
        activeConnections.remove(id)
        removeCall(id)
    }


    fun toggleMute() { onMuteRequested?.invoke(!_state.value.isMuted) }
    fun toggleSpeaker() { onSpeakerRequested?.invoke(!_state.value.isSpeakerOn) }

    private fun isCloaked(info: CallMetadata, newState: Int): Boolean {
        if (!info.isSimulated) return false
        return (info.phase == SimulationPhase.DIALING || info.phase == SimulationPhase.RINGING) && 
               newState == Call.STATE_ACTIVE
    }

    private fun callStateToString(state: Int): String {
        return when (state) {
            Call.STATE_NEW -> "NEW"
            Call.STATE_RINGING -> "RINGING"
            Call.STATE_DIALING -> "DIALING"
            Call.STATE_ACTIVE -> "ACTIVE"
            Call.STATE_HOLDING -> "HOLDING"
            Call.STATE_DISCONNECTED -> "DISCONNECTED"
            Call.STATE_CONNECTING -> "CONNECTING"
            Call.STATE_SELECT_PHONE_ACCOUNT -> "SELECT_PHONE_ACCOUNT"
            Call.STATE_DISCONNECTING -> "DISCONNECTING"
            else -> "UNKNOWN($state)"
        }
    }
}

