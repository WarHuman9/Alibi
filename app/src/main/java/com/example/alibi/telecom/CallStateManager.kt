package com.example.alibi.telecom

import android.telecom.Call
import android.util.Log
import com.example.alibi.telecom.session.RealCallSession
import com.example.alibi.util.getAlibiId
import kotlinx.collections.immutable.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*

/**
 * Bridge between the new Session architecture and the legacy UI state.
 * Optimized with Granular Flows and Immutable Collections.
 */
object CallStateManager {
    private const val TAG = "CallStateManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 1. Primitive Flows (Split from CallManagerState)
    private val _currentCallId = MutableStateFlow<String?>(null)
    val currentCallId: StateFlow<String?> = _currentCallId.asStateFlow()

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    // 2. Structural Flows (Derived from Repository)
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeCalls: StateFlow<PersistentMap<String, CallMetadata>> = CallRepository.sessions
        .flatMapLatest { sessions ->
            if (sessions.isEmpty()) flowOf(persistentMapOf<String, CallMetadata>())
            else combine(sessions.values.map { it.metadata }) { it.associateBy { it.id }.toPersistentMap() }
        }
        .stateIn(scope, SharingStarted.Eagerly, persistentMapOf())

    val activeCallIds: StateFlow<PersistentSet<String>> = CallRepository.sessions
        .map { it.keys.toPersistentSet() }
        .stateIn(scope, SharingStarted.Eagerly, kotlinx.collections.immutable.persistentSetOf())

    // Helper to avoid re-calculating the whole map for observers who only care about one call
    @OptIn(ExperimentalCoroutinesApi::class)
    fun getCallMetadata(id: String): Flow<CallMetadata?> {
        return CallRepository.sessions.map { it[id] }
            .flatMapLatest { it?.metadata ?: flowOf(null) }
            .distinctUntilChanged()
    }

    // 3. Unified Legacy State (For backward compatibility with minimum diffing)
    val state: StateFlow<CallManagerState> = combine(
        activeCalls,
        _currentCallId,
        _isMuted,
        _isSpeakerOn
    ) { calls, currentId, muted, speaker ->
        CallManagerState(
            activeCalls = calls,
            currentCallId = currentId,
            isMuted = muted,
            isSpeakerOn = speaker,
            isHolding = calls.values.any { it.isHolding }
        )
    }
    .stateIn(scope, SharingStarted.Eagerly, CallManagerState())

    private val _actions = MutableSharedFlow<Pair<String, CallAction>>(
        replay = 1,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val actions: SharedFlow<Pair<String, CallAction>> = _actions.asSharedFlow()

    // 4. Derived Logic
    val totalActiveCalls: StateFlow<Int> = activeCallIds.map { it.size }.stateIn(scope, SharingStarted.Eagerly, 0)

    val isSimulatedCallActive: StateFlow<Boolean> = activeCalls
        .map { calls -> calls.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentCall: StateFlow<Call?> = state
        .map { s -> s.activeCalls[s.currentCallId]?.call ?: s.activeCalls.values.find { it.state == Call.STATE_ACTIVE }?.call }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val isRealCall: StateFlow<Boolean> = activeCalls
        .map { calls -> calls.values.any { it.isRealCall && it.state != Call.STATE_DISCONNECTED } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isBusy: StateFlow<Boolean> = activeCalls
        .map { calls -> calls.values.any { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val busyMessage: StateFlow<String?> = CallCoordinator.busyMessage

    val isHolding: StateFlow<Boolean> = state.map { it.isHolding }.stateIn(scope, SharingStarted.Eagerly, false)

    val isCloaking: Boolean
        get() {
            val active = activeCalls.value.values.find { it.phase != SimulationPhase.IDLE }
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

    fun setIsMuted(muted: Boolean) { _isMuted.value = muted }
    fun setIsSpeakerOn(on: Boolean) { _isSpeakerOn.value = on }
    fun setIsHolding(holding: Boolean, id: String? = null) { /* Handled by session metadata updates */ }

    fun toggleMute() { onMuteRequested?.invoke(!isMuted.value) }
    fun toggleSpeaker() { onSpeakerRequested?.invoke(!isSpeakerOn.value) }

    fun updateAudioState(muted: Boolean, speaker: Boolean) {
        _isMuted.value = muted
        _isSpeakerOn.value = speaker
    }

    // --- Session-based entry points ---

    fun onCallAdded(call: Call, isSimulated: Boolean? = null, handler: android.os.Handler? = null) {
        val id = call.getAlibiId()
        if (CallRepository.getSession(id) != null) return

        val isSimulatedCall = isSimulated ?: (call.details.extras?.containsKey(TelecomConstants.EXTRA_ALIBI_CALL_ID) == true)
        
        if (!isSimulatedCall) {
            val session = RealCallSession(call, id)
            CallRepository.addSession(session)
            _currentCallId.value = id
        }
    }

    fun onCallRemoved(call: Call) {
        val id = call.getAlibiId()
        (CallRepository.getSession(id) as? RealCallSession)?.cleanup()
        removeCall(id)
    }

    fun removeCall(id: String) {
        CallRepository.removeSession(id)
        if (_currentCallId.value == id) {
            // Smart Promotion: Pick next best call
            val remaining = activeCalls.value - id
            val nextId = remaining.values.find { it.state == Call.STATE_ACTIVE }?.id
                ?: remaining.values.find { it.state == Call.STATE_RINGING }?.id
                ?: remaining.keys.lastOrNull()
            
            Log.d(TAG, "Smart Promotion: $id removed, next primary is $nextId")
            _currentCallId.value = nextId
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
        val session = CallRepository.getSession(id) ?: return null
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
        val targetId = id ?: currentCallId.value ?: return
        CallRepository.getSession(targetId)?.answer()
    }

    fun disconnect(id: String? = null) {
        val targetId = id ?: currentCallId.value ?: return
        CallRepository.getSession(targetId)?.hangup()
    }

    fun hold(id: String? = null) {
        val targetId = id ?: currentCallId.value ?: return
        CallRepository.getSession(targetId)?.hold()
    }

    fun resume(id: String? = null) {
        val targetId = id ?: currentCallId.value ?: return
        CallRepository.getSession(targetId)?.resume()
    }

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
        _currentCallId.value = id
    }

    fun unregisterConnection(id: String) { removeCall(id) }

    fun clearAllCalls() {
        CallRepository.sessions.value.values.forEach { it.hangup() }
        CallRepository.clear()
        _currentCallId.value = null
    }

    fun addCall(id: String, info: CallMetadata) {
        Log.d(TAG, "addCall (optimistic): $id")
        if (CallRepository.getSession(id) == null) {
            CallRepository.addSession(com.example.alibi.telecom.session.OptimisticCallSession(info))
            _currentCallId.value = id
        }
    }

    fun setSimulatedCallActive(request: SimulatedCallRequest) {
        val targetId = request.alibiId
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
            _currentCallId.value = targetId
        }
    }
    
    fun isCurrentCallSimulated(): Boolean {
        return activeCalls.value.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED }
    }
}
