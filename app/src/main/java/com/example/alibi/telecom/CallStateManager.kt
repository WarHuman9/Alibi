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
 */
data class CallInfo(
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

object CallStateManager {
    private const val TAG = "CallStateManager"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val activeConnections = mutableMapOf<String, SimulatedConnection>()

    // Pending metadata for simulated calls (connectionId -> Map of updates)
    private val pendingMetadata = java.util.concurrent.ConcurrentHashMap<String, (CallInfo) -> CallInfo>()

    private val _activeCalls = MutableStateFlow<Map<String, CallInfo>>(emptyMap())
    val activeCalls: StateFlow<Map<String, CallInfo>> = _activeCalls.asStateFlow()

    val totalActiveCalls: StateFlow<Int> = _activeCalls
        .map { it.size }
        .stateIn(scope, SharingStarted.Eagerly, 0)

    val isSimulatedCallActive: StateFlow<Boolean> = _activeCalls
        .map { map -> map.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isSimulationActive: StateFlow<Boolean> = _activeCalls
        .map { map ->
            map.values.any { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
        }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val currentCall: StateFlow<Call?> = _activeCalls
        .map { map -> 
            // Prefer active call, then last added
            map.values.find { it.state == Call.STATE_ACTIVE }?.call ?: map.values.lastOrNull()?.call 
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val callState: StateFlow<Int> = _activeCalls
        .map { map -> 
            map.values.find { it.state == Call.STATE_ACTIVE }?.state ?: map.values.lastOrNull()?.state ?: Call.STATE_DISCONNECTED 
        }
        .stateIn(scope, SharingStarted.Eagerly, Call.STATE_DISCONNECTED)

    val simulationPhase: StateFlow<SimulationPhase> = _activeCalls
        .map { map -> 
            map.values.find { it.phase != SimulationPhase.IDLE }?.phase ?: SimulationPhase.IDLE 
        }
        .stateIn(scope, SharingStarted.Eagerly, SimulationPhase.IDLE)

    val isRealCall: StateFlow<Boolean> = _activeCalls
        .map { map -> map.values.any { it.isRealCall } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isHolding: StateFlow<Boolean> = _activeCalls
        .map { map -> map.values.any { it.isHolding } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val isBusy: StateFlow<Boolean> = _activeCalls
        .map { it.isNotEmpty() }
        .stateIn(scope, SharingStarted.Eagerly, false)

    val busyMessage: StateFlow<String?> = _activeCalls
        .map { map ->
            when {
                map.values.any { it.isRealCall } -> "A real call is currently happening. Try again later."
                map.values.any { it.isSimulated } -> "There is already an ongoing Simulated call. Try again later."
                else -> null
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    val answerTime: StateFlow<Long> = _activeCalls
        .map { map -> map.values.lastOrNull()?.answerTime ?: 0L }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    val logCallType: StateFlow<Int> = _activeCalls
        .map { map -> map.values.lastOrNull()?.type ?: CallLog.Calls.INCOMING_TYPE }
        .stateIn(scope, SharingStarted.Eagerly, CallLog.Calls.INCOMING_TYPE)

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _isSpeakerOn = MutableStateFlow(false)
    val isSpeakerOn: StateFlow<Boolean> = _isSpeakerOn.asStateFlow()

    // Configuration state (transient, used for next simulated call)
    private val _customStartTime = MutableStateFlow<Long?>(null)
    private val _intendedDuration = MutableStateFlow<Long?>(null)
    private val _mimicSimHandle = MutableStateFlow<PhoneAccountHandle?>(null)
    private val _callFeatures = MutableStateFlow(0)


    val isCloaking: Boolean
        get() {
            val active = activeCalls.value.values.find { it.phase != SimulationPhase.IDLE }
            return (active?.phase == SimulationPhase.DIALING || active?.phase == SimulationPhase.RINGING) &&
                    (active.state == Call.STATE_ACTIVE)
        }

    /**
     * Immutable snapshot of a call's metadata for logging.
     */
    data class CallMetadata(
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
     * Captures the current call state into an immutable snapshot.
     */
    fun captureSnapshot(id: String? = null): CallMetadata? {
        val targetId = id ?: activeCalls.value.keys.lastOrNull() ?: return null
        val info = activeCalls.value[targetId] ?: return null
        return CallMetadata(
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
        val isReal = activeCalls.value[id]?.isRealCall == true
        Log.d("Alibi_State", "updateCallState: id=$id, state=${callStateToString(state)}, isReal=$isReal")
        
        _activeCalls.update { map ->
            map[id]?.let { info ->
                var newPhase = info.phase
                var newIsHolding = info.isHolding
                var newAnswerTime = info.answerTime

                // Update phase based on state. 
                // For simulated calls, we respect the "Cloaking" state where UI phase is RINGING/DIALING but system state is ACTIVE.
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
                } else {
                    Log.d(TAG, "Call $id is currently cloaked. Preserving phase: ${info.phase}")
                }

                newIsHolding = (state == Call.STATE_HOLDING)
                if (state == Call.STATE_ACTIVE && newAnswerTime == 0L) {
                    newAnswerTime = System.currentTimeMillis()
                }

                map + (id to info.copy(
                    state = state,
                    phase = newPhase,
                    isHolding = newIsHolding,
                    answerTime = newAnswerTime
                ))
            } ?: map
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
                if (!activeCalls.value.containsKey(alibiId)) {
                    Log.i(TAG, "Alibi ID arrived in details. Re-keying $oldId -> $alibiId")
                    rekeyCall(oldId, alibiId)
                }
            }
        }
    }

    fun addCall(id: String, info: CallInfo) {
        Log.d("Alibi_State", "addCall: id=$id, isSimulated=${info.isSimulated}")
        _activeCalls.update { currentMap ->
            val existing = currentMap[id]
            if (existing != null) {
                Log.d(TAG, "Merging system call object into optimistic entry for $id")
                currentMap + (id to existing.copy(
                    call = info.call,
                    isRealCall = info.isRealCall,
                    // Preserve metadata if it was set optimistically
                    number = if (info.number != "Unknown") info.number else existing.number,
                    state = if (info.state != Call.STATE_DISCONNECTED) info.state else existing.state,
                    startTime = if (info.startTime != 0L) info.startTime else existing.startTime
                ))
            } else {
                currentMap + (id to info)
            }
        }
    }

    /**
     * Task 15: Re-key logic to synchronize UI (hashcode) with Simulation (UUID).
     */
    fun rekeyCall(oldId: String, newId: String) {
        Log.d("Alibi_State", "rekeyCall: mapping $oldId -> $newId")
        _activeCalls.update { currentMap ->
            val info = currentMap[oldId] ?: return@update currentMap
            val newMap = currentMap.toMutableMap()
            newMap.remove(oldId)
            newMap[newId] = info.copy(id = newId)
            newMap
        }
        
        // Also update pending metadata if any
        pendingMetadata.remove(oldId)?.let { updateFunc ->
            pendingMetadata[newId] = updateFunc
        }
    }

    fun removeCall(id: String) {
        Log.d("Alibi_State", "removeCall: Atomic removal for id=$id")
        _activeCalls.update { currentMap ->
            if (!currentMap.containsKey(id)) return@update currentMap
            val newMap = currentMap.toMutableMap()
            newMap.remove(id)
            newMap
        }
        pendingMetadata.remove(id)
    }

    /**
     * Emergency reset for the call manager.
     */
    fun clearAllCalls() {
        Log.w(TAG, "Emergency reset: Clearing all active calls and metadata.")
        _activeCalls.value = emptyMap()
        pendingMetadata.clear()
        activeConnections.clear()
    }


    fun setCustomStartTime(timestamp: Long?) { _customStartTime.value = timestamp }
    fun setIntendedDuration(duration: Long?) { _intendedDuration.value = duration }
    fun setMimicSimHandle(handle: PhoneAccountHandle?) { _mimicSimHandle.value = handle }
    fun setCallFeatures(features: Int) { _callFeatures.value = features }


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

    var info = CallInfo(
        id = id,
        call = call,
        isSimulated = isSimulatedCall,
        isRealCall = isRealCall,
        number = call.details.handle?.schemeSpecificPart ?: "Unknown",
        name = name,
        state = state,
        phase = initialPhase,
            startTime = _customStartTime.value ?: System.currentTimeMillis(),
            answerTime = if (state == Call.STATE_ACTIVE) System.currentTimeMillis() else 0L,
            simHandle = _mimicSimHandle.value,
            features = _callFeatures.value,
            isHolding = (state == Call.STATE_HOLDING)
        )
        
        // Apply any pending metadata
        pendingMetadata.remove(id)?.let { updateFunc ->
            Log.d(TAG, "Applying pending metadata for call: $id")
            info = updateFunc(info)
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
            val updateFunc: (CallInfo) -> CallInfo = { info ->
                var newAnswerTime = info.answerTime
                val newPhase = when (state) {
                    Call.STATE_RINGING -> SimulationPhase.RINGING
                    Call.STATE_DIALING -> SimulationPhase.DIALING
                    Call.STATE_ACTIVE -> {
                        if (newAnswerTime == 0L) newAnswerTime = System.currentTimeMillis()
                        SimulationPhase.SIMULATED_ACTIVE
                    }
                    Call.STATE_HOLDING -> SimulationPhase.HOLDING
                    else -> info.phase
                }
                
                info.copy(
                    isSimulated = true,
                    number = phoneNumber ?: info.number,
                    name = phoneNumber ?: info.name,
                    state = state,
                    phase = newPhase,
                    answerTime = newAnswerTime,
                    isHolding = (state == Call.STATE_HOLDING),
                    type = type ?: info.type
                )
            }

            _activeCalls.update { map ->
                map[targetId]?.let { info ->
                    map + (targetId to updateFunc(info))
                } ?: run {
                    Log.d(TAG, "Call $targetId not found yet. Queueing metadata and Injecting optimistic call.")
                    pendingMetadata[targetId] = updateFunc
                    
                    // Direct Injection: Create the optimistic entry immediately
                    val optimistic = updateFunc(CallInfo(
                        id = targetId,
                        isSimulated = true,
                        number = phoneNumber ?: "Unknown",
                        name = phoneNumber ?: "Unknown",
                        state = state
                    ))
                    map + (targetId to optimistic)
                }
            }
        } else {
            removeCall(targetId)
        }
    }


    // --- Termination & Logging ---

    suspend fun terminateSimulatedSession(
        context: Context,
        snapshot: CallMetadata,
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
        snapshot: CallMetadata,
        intendedDuration: Long?
    ) = withContext(Dispatchers.IO + NonCancellable) {
        Log.d(TAG, "Recording call log for ${snapshot.number}. Start: ${snapshot.startTime}, Answer: ${snapshot.answerTime}")

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
        Log.d(TAG, "Call log inserted successfully: ${snapshot.number}, dur: $finalDuration")
    }

    // --- UI Actions ---

    fun isCurrentCallSimulated(): Boolean {
        return activeCalls.value.values.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED }
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
        val isReal = activeCalls.value.values.any { it.isRealCall }
        if (isReal) {
            Log.d("Alibi_RealAudio", "Real Audio Transition: muted=$muted, speaker=$speaker")
            _isMuted.value = muted
            _isSpeakerOn.value = speaker
            return // Simply update flows, DO NOT trigger side effects (e.g. Heartbeat Manager)
        }

        Log.d("Alibi_State", "updateAudioState: muted=$muted, speaker=$speaker")
        _isMuted.value = muted
        _isSpeakerOn.value = speaker
    }


    fun setIsHolding(holding: Boolean, id: String? = null) {
        val targetId = id ?: activeConnections.keys.lastOrNull() ?: return
        _activeCalls.update { map ->
            map[targetId]?.let { info ->
                map + (targetId to info.copy(isHolding = holding))
            } ?: map
        }
    }


    fun registerConnection(id: String, connection: SimulatedConnection) {
        Log.d(TAG, "Registering connection: $id")
        activeConnections[id] = connection
        
        // Task 15: Direct Injection - Check for pending metadata
        // We remove it here to consume it, as registerConnection is the first service-level handshake
        pendingMetadata.remove(id)?.let { updateFunc ->
            Log.d(TAG, "Found pending metadata for $id during registration. Updating/Creating optimistic entry.")
            _activeCalls.update { map ->
                val info = map[id] ?: CallInfo(id = id, isSimulated = true)
                map + (id to updateFunc(info))
            }
        }
    }

    fun unregisterConnection(id: String, context: Context) {
        Log.d(TAG, "Unregistering connection: $id")
        activeConnections.remove(id)
        removeCall(id)
    }


    fun toggleMute() { onMuteRequested?.invoke(!_isMuted.value) }
    fun toggleSpeaker() { onSpeakerRequested?.invoke(!_isSpeakerOn.value) }

    private fun isCloaked(info: CallInfo, newState: Int): Boolean {
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
