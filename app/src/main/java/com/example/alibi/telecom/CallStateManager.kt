package com.example.alibi.telecom

import android.provider.CallLog
import android.telecom.Call
import android.util.Log
import com.example.alibi.telecom.session.CallSession
import com.example.alibi.telecom.session.RealCallSession
import com.example.alibi.util.getAlibiId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * Bridge between the new Session architecture and the legacy UI state.
 */
object CallStateManager {
    private const val TAG = "CallStateManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Internal state for non-session properties
    private val _state = MutableStateFlow(CallManagerState())
    
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<CallManagerState> = CallRepository.sessions
        .flatMapLatest { sessions ->
            if (sessions.isEmpty()) flowOf(emptyMap<String, CallMetadata>())
            else combine(sessions.values.map { it.metadata }) { it.associateBy { it.id } }
        }
        .combine(_state) { activeCalls, internal ->
            internal.copy(
                activeCalls = activeCalls,
                isHolding = activeCalls.values.any { it.isHolding }
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, CallManagerState())

    private val _actions = MutableSharedFlow<Pair<String, CallAction>>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val actions: SharedFlow<Pair<String, CallAction>> = _actions.asSharedFlow()

    // Derived properties - Now delegating to new components
    val activeCalls: StateFlow<Map<String, CallMetadata>> = state
        .map { it.activeCalls }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val totalActiveCalls: StateFlow<Int> = activeCalls
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val isSimulatedCallActive: StateFlow<Boolean> = activeCalls
        .map { calls -> calls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentCall: StateFlow<Call?> = state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.call 
                ?: s.activeCalls.values.find { it.state == Call.STATE_ACTIVE }?.call 
                ?: s.activeCalls.values.lastOrNull()?.call 
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isRealCall: StateFlow<Boolean> = activeCalls
        .map { calls -> calls.values.any { it.isRealCall && it.state != Call.STATE_DISCONNECTED } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isBusy: StateFlow<Boolean> = activeCalls
        .map { calls -> calls.values.any { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val busyMessage: StateFlow<String?> = CallCoordinator.busyMessage

    val isHolding: StateFlow<Boolean> = state
        .map { it.isHolding }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isCloaking: Boolean
        get() {
            val active = state.value.activeCalls.values.find { it.phase != SimulationPhase.IDLE }
            return (active?.phase == SimulationPhase.DIALING || active?.phase == SimulationPhase.RINGING) &&
                    (active?.state == Call.STATE_ACTIVE)
        }

    val callState: StateFlow<Int> = state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.state 
                ?: s.activeCalls.values.find { it.state == Call.STATE_ACTIVE }?.state 
                ?: s.activeCalls.values.lastOrNull()?.state 
                ?: Call.STATE_DISCONNECTED 
        }
        .stateIn(scope, SharingStarted.Eagerly, Call.STATE_DISCONNECTED)

    val simulationPhase: StateFlow<SimulationPhase> = state
        .map { s -> 
            s.activeCalls[s.currentCallId]?.phase 
                ?: s.activeCalls.values.find { it.phase != SimulationPhase.IDLE }?.phase 
                ?: SimulationPhase.IDLE 
        }
        .stateIn(scope, SharingStarted.Eagerly, SimulationPhase.IDLE)

    val isMuted: StateFlow<Boolean> = _state.map { it.isMuted }.stateIn(scope, SharingStarted.Eagerly, false)
    val isSpeakerOn: StateFlow<Boolean> = _state.map { it.isSpeakerOn }.stateIn(scope, SharingStarted.Eagerly, false)

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

    fun setIsMuted(muted: Boolean) {
        _state.update { it.copy(isMuted = muted) }
    }

    fun setIsSpeakerOn(on: Boolean) {
        _state.update { it.copy(isSpeakerOn = on) }
    }

    fun setIsHolding(holding: Boolean, id: String? = null) {
        _state.update { it.copy(isHolding = holding) }
    }

    fun toggleMute() { onMuteRequested?.invoke(!_state.value.isMuted) }
    fun toggleSpeaker() { onSpeakerRequested?.invoke(!_state.value.isSpeakerOn) }

    fun updateAudioState(muted: Boolean, speaker: Boolean) {
        _state.update { it.copy(isMuted = muted, isSpeakerOn = speaker) }
    }

    // --- Session-based entry points ---

    fun onCallAdded(call: Call, isSimulated: Boolean? = null, handler: android.os.Handler? = null) {
        val id = call.getAlibiId()
        
        // Check if already registered
        if (CallRepository.getSession(id) != null) return

        val isSimulatedCall = isSimulated ?: (call.details.extras?.containsKey(TelecomConstants.EXTRA_ALIBI_CALL_ID) == true)
        
        if (!isSimulatedCall) {
            val session = RealCallSession(call, id)
            CallRepository.addSession(session)
            
            // Sync current ID
            _state.update { it.copy(currentCallId = id) }
        }
    }

    fun onCallRemoved(call: Call) {
        val id = call.getAlibiId()
        (CallRepository.getSession(id) as? RealCallSession)?.cleanup()
        removeCall(id)
    }

    fun removeCall(id: String) {
        CallRepository.removeSession(id)
        _state.update { s ->
            if (s.currentCallId == id) {
                // Smart Promotion: Pick next best call
                val remaining = state.value.activeCalls - id
                val nextId = remaining.values.find { it.state == Call.STATE_ACTIVE }?.id
                    ?: remaining.values.find { it.state == Call.STATE_RINGING }?.id
                    ?: remaining.keys.lastOrNull()
                
                Log.d(TAG, "Smart Promotion: $id removed, next primary is $nextId")
                s.copy(currentCallId = nextId)
            } else s
        }
    }

    fun getCallId(call: Call): String? {
        val details = call.details
        val extras = details.extras ?: android.os.Bundle.EMPTY
        val alibiId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        if (alibiId != null) return alibiId
        
        return CallRepository.sessions.value.values
            .filterIsInstance<RealCallSession>()
            .find { it.metadata.value.call == call }?.id
    }

    fun completeCall(id: String): CallLogSnapshot? {
        val session = CallRepository.getSession(id)
        if (session == null) {
            Log.w(TAG, "completeCall: Session not found for $id. Available: ${CallRepository.sessions.value.keys}")
            return null
        }
        val meta = session.metadata.value
        val snapshot = CallLogSnapshot(
            number = meta.number,
            type = meta.type,
            startTime = meta.startTime,
            answerTime = meta.answerTime,
            endTime = System.currentTimeMillis(),
            simHandle = meta.simHandle,
            features = meta.features,
            isSimulated = meta.isSimulated
        )
        removeCall(id)
        return snapshot
    }

    fun answer(id: String? = null) {
        val targetId = id ?: state.value.currentCallId ?: return
        CallRepository.getSession(targetId)?.answer()
    }

    fun disconnect(id: String? = null) {
        val targetId = id ?: state.value.currentCallId ?: return
        CallRepository.getSession(targetId)?.hangup()
    }

    fun hold(id: String? = null) {
        val targetId = id ?: state.value.currentCallId ?: return
        CallRepository.getSession(targetId)?.hold()
    }

    fun resume(id: String? = null) {
        val targetId = id ?: state.value.currentCallId ?: return
        CallRepository.getSession(targetId)?.resume()
    }

    // For compatibility with SimulationController
    fun updateCallState(id: String, state: Int, overridePhase: SimulationPhase? = null) {
        val session = CallRepository.getSession(id)
        when (session) {
            is com.example.alibi.telecom.session.SimulatedCallSession -> session.updateState(state, overridePhase)
            is com.example.alibi.telecom.session.OptimisticCallSession -> session.updateState(state, overridePhase)
        }
    }

    fun registerConnection(id: String, connection: com.example.alibi.service.SimulatedConnection) {
        val session = com.example.alibi.telecom.session.SimulatedCallSession(connection, id)
        CallRepository.addSession(session)
        _state.update { it.copy(currentCallId = id) }
    }

    fun unregisterConnection(id: String) {
        removeCall(id)
    }

    fun clearAllCalls() {
        CallRepository.sessions.value.values.forEach { it.hangup() }
        CallRepository.clear()
        _state.update { CallManagerState() }
    }

    // Legacy method for DialerScreen optimistic calls
    fun addCall(id: String, info: CallMetadata) {
        Log.d(TAG, "addCall (optimistic): $id")
        if (CallRepository.getSession(id) == null) {
            CallRepository.addSession(com.example.alibi.telecom.session.OptimisticCallSession(info))
            _state.update { it.copy(currentCallId = id) }
        }
    }

    fun setSimulatedCallActive(request: SimulatedCallRequest) {
        val targetId = request.alibiId
        Log.d(TAG, "setSimulatedCallActive (optimistic): $targetId")
        
        if (CallRepository.getSession(targetId) == null) {
            val optimistic = CallMetadata(
                id = targetId,
                isSimulated = true,
                number = request.phoneNumber,
                name = request.phoneNumber,
                state = if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) 
                    android.telecom.Call.STATE_DIALING else android.telecom.Call.STATE_RINGING,
                phase = if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) 
                    SimulationPhase.DIALING else SimulationPhase.RINGING,
                type = request.direction,
                simHandle = request.simHandle,
                features = request.features,
                startTime = request.startTime ?: System.currentTimeMillis()
            )
            CallRepository.addSession(com.example.alibi.telecom.session.OptimisticCallSession(optimistic))
            _state.update { it.copy(currentCallId = targetId) }
        }
    }
    
    fun setSimulatedCallActive(active: Boolean, phoneNumber: String? = null, state: Int = Call.STATE_ACTIVE, type: Int? = null, id: String? = null) {
        // Legacy support
    }
    
    fun isCurrentCallSimulated(): Boolean {
        return state.value.activeCalls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED }
    }
}
