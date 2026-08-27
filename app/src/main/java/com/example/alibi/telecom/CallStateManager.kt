package com.example.alibi.telecom

import android.provider.CallLog
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.util.Log
import com.example.alibi.util.getAlibiId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import android.os.Build

/**
 * Pure data registry for call state across the application.
 * Task 18: Architectural Decoupling. Logic moved to SimulationController.
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

object CallStateManager {
    private const val TAG = "CallStateManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(CallManagerState())
    val state: StateFlow<CallManagerState> = _state.asStateFlow()

    private val _actions = MutableSharedFlow<Pair<String, CallAction>>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val actions: SharedFlow<Pair<String, CallAction>> = _actions.asSharedFlow()

    // Derived properties
    val activeCalls: StateFlow<Map<String, CallMetadata>> = _state
        .map { it.activeCalls }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val totalActiveCalls: StateFlow<Int> = _state
        .map { it.activeCalls.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val isSimulatedCallActive: StateFlow<Boolean> = _state
        .map { s -> s.activeCalls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentCall: StateFlow<Call?> = _state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.call 
                ?: s.activeCalls.values.find { it.state == Call.STATE_ACTIVE }?.call 
                ?: s.activeCalls.values.lastOrNull()?.call 
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isRealCall: StateFlow<Boolean> = _state
        .map { s -> s.activeCalls.values.any { it.isRealCall } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isBusy: StateFlow<Boolean> = _state
        .map { s -> s.activeCalls.values.any { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val busyMessage: StateFlow<String?> = _state
        .map { s ->
            val active = s.activeCalls.values.filter { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
            when {
                active.any { it.isRealCall } -> "A real call is currently happening. Try again later."
                active.any { it.isSimulated } -> "There is already an ongoing Simulated call. Try again later."
                else -> null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isHolding: StateFlow<Boolean> = _state
        .map { it.isHolding }
        .stateIn(scope, SharingStarted.Eagerly, false)

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

    val isMuted: StateFlow<Boolean> = _state.map { it.isMuted }.stateIn(scope, SharingStarted.Eagerly, false)
    val isSpeakerOn: StateFlow<Boolean> = _state.map { it.isSpeakerOn }.stateIn(scope, SharingStarted.Eagerly, false)

    val isCloaking: Boolean
        get() {
            val active = _state.value.activeCalls.values.find { it.phase != SimulationPhase.IDLE }
            return (active?.phase == SimulationPhase.DIALING || active?.phase == SimulationPhase.RINGING) &&
                    (active?.state == Call.STATE_ACTIVE)
        }

    @Volatile var onCallStateChangedHook: ((Call, Int) -> Unit)? = null
    @Volatile var onMuteRequested: ((Boolean) -> Unit)? = null
    @Volatile var onSpeakerRequested: ((Boolean) -> Unit)? = null

    @Volatile private var isAudioHandlerPriority = false

    @Synchronized
    fun setAudioHandlers(mute: (Boolean) -> Unit, speaker: (Boolean) -> Unit, priority: Boolean = false) {
        if (priority || !isAudioHandlerPriority || onMuteRequested == null) {
            onMuteRequested = mute
            onSpeakerRequested = speaker
            isAudioHandlerPriority = priority
        }
    }

    @Synchronized
    fun clearAudioHandlers(priority: Boolean) {
        if (priority == isAudioHandlerPriority) {
            onMuteRequested = null
            onSpeakerRequested = null
            isAudioHandlerPriority = false
        }
    }

    fun updateCallState(id: String, state: Int, overridePhase: SimulationPhase? = null) {
        _state.update { s ->
            s.activeCalls[id]?.let { info ->
                var newPhase = overridePhase ?: info.phase
                var newAnswerTime = info.answerTime
                
                if (overridePhase != null) {
                    // Forced phase transition (e.g. from SimulationController)
                    if (overridePhase == SimulationPhase.SIMULATED_ACTIVE && newAnswerTime == 0L) {
                        newAnswerTime = System.currentTimeMillis()
                    }
                } else {
                    // Natural state-driven transition
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
                }

                val updatedInfo = info.copy(
                    state = state,
                    phase = newPhase,
                    isHolding = (state == Call.STATE_HOLDING),
                    answerTime = newAnswerTime
                )

                if (state == Call.STATE_DISCONNECTED) {
                    Log.d(TAG, "updateCallState: Call $id disconnected")
                }
                
                val newActiveCalls = s.activeCalls + (id to updatedInfo)
                s.copy(
                    activeCalls = newActiveCalls,
                    isHolding = newActiveCalls.values.any { it.isHolding },
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
                    rekeyCall(oldId, alibiId)
                }
            }
        }
    }

    fun addCall(id: String, info: CallMetadata) {
        Log.d(TAG, "addCall: id=$id, isSimulated=${info.isSimulated}, state=${info.state}")
        _state.update { s ->
            val existing = s.activeCalls[id]
            val updatedInfo = existing?.copy(
                call = info.call,
                isRealCall = info.isRealCall,
                number = if (info.number != "Unknown") info.number else existing.number,
                state = if (info.state != Call.STATE_DISCONNECTED) info.state else existing.state,
                startTime = if (info.startTime != 0L) info.startTime else existing.startTime
            ) ?: info
            
            val newActiveCalls = s.activeCalls + (id to updatedInfo)
            s.copy(
                activeCalls = newActiveCalls,
                currentCallId = if (updatedInfo.state == Call.STATE_ACTIVE || s.currentCallId == null) id else s.currentCallId
            )
        }
    }

    fun rekeyCall(oldId: String, newId: String) {
        Log.d(TAG, "rekeyCall: $oldId -> $newId")
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
        val now = System.currentTimeMillis()
        Log.d(TAG, "[$now] removeCall: id=$id")
        _state.update { s ->
            val info = s.activeCalls[id]
            if (info == null) {
                Log.w(TAG, "removeCall: ID $id not found. Current IDs: ${s.activeCalls.keys}")
                return@update s
            }

            val newActiveCalls = s.activeCalls.toMutableMap()
            newActiveCalls.remove(id)
            
            // CONTINUITY FIX: If we just removed a system call, also clear any lingering "REAL_" optimistic 
            // placeholders for the same number to ensure the UI and "Busy" state reset instantly.
            if (!id.startsWith("REAL_") && info.isRealCall) {
                val staleOptimisticId = newActiveCalls.entries.find { 
                    it.key.startsWith("REAL_") && it.value.number == info.number 
                }?.key
                if (staleOptimisticId != null) {
                    Log.d(TAG, "removeCall: Also clearing stale optimistic entry $staleOptimisticId")
                    newActiveCalls.remove(staleOptimisticId)
                }
            }
            
            val newPendingMetadata = s.pendingMetadata.toMutableMap()
            newPendingMetadata.remove(id)
            
            s.copy(
                activeCalls = newActiveCalls,
                pendingMetadata = newPendingMetadata,
                currentCallId = if (s.currentCallId == id) newActiveCalls.keys.lastOrNull() else s.currentCallId
            )
        }
    }

    fun getCallId(call: Call): String? {
        return _state.value.activeCalls.entries.find { it.value.call == call }?.key
    }

    /**
     * Atomically captures call metadata for logging and removes it from the active registry.
     * Task 21: Atomic Logging Synchronization.
     */
    fun completeCall(id: String): CallLogSnapshot? {
        val now = System.currentTimeMillis()
        Log.d(TAG, "[$now] completeCall: id=$id")
        val info = _state.value.activeCalls[id] ?: return null
        val snapshot = CallLogSnapshot(
            number = info.number,
            type = info.type,
            startTime = info.startTime,
            answerTime = info.answerTime,
            endTime = System.currentTimeMillis(),
            simHandle = info.simHandle,
            features = info.features,
            isSimulated = info.isSimulated
        )
        removeCall(id)
        return snapshot
    }

    fun clearAllCalls() {
        val activeIds = _state.value.activeCalls.keys
        activeIds.forEach { disconnect(it) }
        _state.value = CallManagerState()
    }

    fun onCallAdded(call: Call, isSimulated: Boolean? = null, handler: android.os.Handler? = null) {
        val id = call.getAlibiId()
        
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        if (state == Call.STATE_DISCONNECTED) {
            Log.d(TAG, "onCallAdded: Call $id already disconnected. Aborting.")
            removeCall(id)
            return
        }
        
        val isSimulatedCall = isSimulated ?: (call.details.extras?.containsKey(TelecomConstants.EXTRA_ALIBI_CALL_ID) == true)
        val isRealCall = !isSimulatedCall

        // BUG FIX: Merge optimistic real call (REAL_UUID) with system call
        if (isRealCall) {
            val handle = call.details.handle?.schemeSpecificPart
            if (handle != null) {
                val optimisticId = _state.value.activeCalls.entries.find { 
                    it.value.isRealCall && it.value.number == handle && it.key.startsWith("REAL_")
                }?.key
                if (optimisticId != null && optimisticId != id) {
                    Log.d(TAG, "onCallAdded: Merging optimistic real call $optimisticId into system call $id")
                    rekeyCall(optimisticId, id)
                }
            }
        }
    
        if (isRealCall && isSimulatedCallActive.value) {
            _state.value.activeCalls.filterValues { it.isSimulated }.forEach { (callId, _) ->
                com.example.alibi.service.SimulationController.disconnectSimulatedCall(callId)
            }
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
            name = call.details.callerDisplayName ?: call.details.handle?.schemeSpecificPart ?: "Unknown",
            state = state,
            phase = initialPhase,
            startTime = System.currentTimeMillis(),
            answerTime = if (state == Call.STATE_ACTIVE) System.currentTimeMillis() else 0L,
            simHandle = if (isRealCall) call.details.accountHandle else call.details.accountHandle,
            features = 0,
            isHolding = (state == Call.STATE_HOLDING)
        )
        
        _state.update { s ->
            val request = s.pendingMetadata[id]
            if (request != null) {
                info = info.copy(
                    number = request.phoneNumber,
                    name = request.phoneNumber,
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
        if (handler != null) call.registerCallback(callCallback, handler) else call.registerCallback(callCallback)
    }

    fun onCallRemoved(call: Call) {
        val id = call.getAlibiId()
        call.unregisterCallback(callCallback)
        removeCall(id)
    }

    fun setSimulatedCallActive(request: SimulatedCallRequest) {
        val targetId = request.alibiId
        _state.update { s ->
            val existing = s.activeCalls[targetId]
            // Calculate state based on direction
            val state = if (request.direction == CallLog.Calls.OUTGOING_TYPE) 
                Call.STATE_DIALING else Call.STATE_RINGING
            
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
                    number = request.phoneNumber,
                    name = request.phoneNumber,
                    state = state,
                    phase = newPhase,
                    answerTime = newAnswerTime,
                    isHolding = (state == Call.STATE_HOLDING),
                    type = request.direction,
                    simHandle = request.simHandle ?: existing.simHandle,
                    features = request.features,
                    startTime = request.startTime ?: existing.startTime
                )
                s.copy(activeCalls = s.activeCalls + (targetId to updated))
            } else {
                val optimistic = CallMetadata(
                    id = targetId,
                    isSimulated = true,
                    number = request.phoneNumber,
                    name = request.phoneNumber,
                    state = state,
                    phase = when (state) {
                        Call.STATE_RINGING -> SimulationPhase.RINGING
                        Call.STATE_DIALING -> SimulationPhase.DIALING
                        else -> SimulationPhase.IDLE
                    },
                    type = request.direction,
                    simHandle = request.simHandle,
                    features = request.features,
                    startTime = request.startTime ?: System.currentTimeMillis()
                )
                s.copy(
                    pendingMetadata = s.pendingMetadata + (targetId to request),
                    activeCalls = s.activeCalls + (targetId to optimistic)
                )
            }
        }
    }

    fun setSimulatedCallActive(active: Boolean, phoneNumber: String? = null, state: Int = Call.STATE_ACTIVE, type: Int? = null, id: String? = null) {
        if (!active && id != null) {
            removeCall(id)
            return
        }
        
        val targetId = id ?: return
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
                val request = SimulatedCallRequest(phoneNumber = phoneNumber ?: "Unknown", direction = type ?: CallLog.Calls.INCOMING_TYPE, alibiId = targetId)
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
                    },
                    type = type ?: CallLog.Calls.INCOMING_TYPE
                )
                s.copy(pendingMetadata = s.pendingMetadata + (targetId to request), activeCalls = s.activeCalls + (targetId to optimistic))
            }
        }
    }

    fun isCurrentCallSimulated(): Boolean {
        return _state.value.activeCalls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED }
    }

    fun answer(id: String? = null) {
        val targetId = id ?: _state.value.currentCallId ?: return
        val callInfo = _state.value.activeCalls[targetId]
        
        // Bug 37: If an explicit ID was provided but isn't in activeCalls, return without acting.
        if (id != null && callInfo == null) {
            Log.w(TAG, "answer: Explicit ID $id not found in active calls. Ignoring.")
            return
        }

        val finalCallInfo = callInfo ?: _state.value.activeCalls.values.find { it.state == Call.STATE_RINGING }
        
        if (finalCallInfo?.isSimulated == true) {
            _actions.tryEmit(finalCallInfo.id to CallAction.ANSWER)
        } else {
            finalCallInfo?.call?.answer(0)
        }
    }

    fun disconnect(id: String? = null) {
        val targetId = id ?: _state.value.currentCallId ?: return
        val callInfo = _state.value.activeCalls[targetId]
        
        // Bug 37: If an explicit ID was provided but isn't in activeCalls, return without acting.
        if (id != null && callInfo == null) {
            Log.w(TAG, "disconnect: Explicit ID $id not found in active calls. Ignoring.")
            return
        }

        val finalCallInfo = callInfo ?: _state.value.activeCalls.values.lastOrNull()
        
        if (finalCallInfo?.isSimulated == true) {
            _actions.tryEmit(finalCallInfo.id to CallAction.HANGUP)
        } else {
            finalCallInfo?.call?.disconnect()
        }
    }

    fun updateAudioState(muted: Boolean, speaker: Boolean) {
        val isReal = _state.value.activeCalls.values.any { it.isRealCall }
        if (isReal) Log.d("Alibi_RealAudio", "Real Audio Transition: muted=$muted, speaker=$speaker")
        _state.update { it.copy(isMuted = muted, isSpeakerOn = speaker) }
    }

    fun setIsHolding(holding: Boolean, id: String? = null) {
        val targetId = id ?: _state.value.currentCallId ?: return
        _state.update { s ->
            s.activeCalls[targetId]?.let { info ->
                val newActiveCalls = s.activeCalls + (targetId to info.copy(isHolding = holding))
                s.copy(activeCalls = newActiveCalls, isHolding = newActiveCalls.values.any { it.isHolding })
            } ?: s
        }
    }

    fun registerConnection(id: String, connection: com.example.alibi.service.SimulatedConnection) {
        _state.update { s ->
            val request = s.pendingMetadata[id]
            if (request != null) {
                val info = s.activeCalls[id] ?: CallMetadata(id = id, isSimulated = true)
                val updatedInfo = info.copy(number = request.phoneNumber, name = request.phoneNumber, startTime = request.startTime ?: info.startTime, simHandle = request.simHandle ?: info.simHandle, features = request.features)
                val newPending = s.pendingMetadata.toMutableMap()
                newPending.remove(id)
                s.copy(activeCalls = s.activeCalls + (id to updatedInfo), pendingMetadata = newPending)
            } else {
                s
            }
        }
    }

    fun unregisterConnection(id: String) {
        removeCall(id)
    }

    fun toggleMute() { onMuteRequested?.invoke(!_state.value.isMuted) }
    fun toggleSpeaker() { onSpeakerRequested?.invoke(!_state.value.isSpeakerOn) }

    private fun isCloaked(info: CallMetadata, newState: Int): Boolean {
        if (!info.isSimulated) return false
        return (info.phase == SimulationPhase.DIALING || info.phase == SimulationPhase.RINGING) && newState == Call.STATE_ACTIVE
    }
}
