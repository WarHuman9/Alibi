package com.example.alibi.telecom

import android.content.Context
import android.provider.CallLog
import android.telecom.Call
import android.telecom.PhoneAccountHandle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Singleton manager for call state across the application.
 * Synchronizes Real and Simulated call states for the UI.
 */
object CallStateManager {
    private const val TAG = "CallStateManager"

    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

    private val _callState = MutableStateFlow(Call.STATE_DISCONNECTED)
    val callState: StateFlow<Int> = _callState.asStateFlow()

    private val _isSimulatedCallActive = MutableStateFlow(false)
    val isSimulatedCallActive: StateFlow<Boolean> = _isSimulatedCallActive.asStateFlow()

    private val _isRealCall = MutableStateFlow(false)
    val isRealCall: StateFlow<Boolean> = _isRealCall.asStateFlow()

    private val _simulatedPhoneNumber = MutableStateFlow<String?>(null)
    val simulatedPhoneNumber: StateFlow<String?> = _simulatedPhoneNumber.asStateFlow()

    private val _startTime = MutableStateFlow(0L)
    val startTime: StateFlow<Long> = _startTime.asStateFlow()

    private val _customStartTime = MutableStateFlow<Long?>(null)
    private val _intendedDuration = MutableStateFlow<Long?>(null)
    private val _mimicSimHandle = MutableStateFlow<PhoneAccountHandle?>(null)
    private val _callFeatures = MutableStateFlow(0)

    private val _isMuted = MutableStateFlow(false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _speakerOn = MutableStateFlow(false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    private val _answerTime = MutableStateFlow(0L)
    val answerTime: StateFlow<Long> = _answerTime.asStateFlow()

    private val _callType = MutableStateFlow(CallLog.Calls.INCOMING_TYPE)
    val logCallType: StateFlow<Int> = _callType.asStateFlow()

    // Request callbacks for UI -> Service interaction
    var onAnswerRequested: (() -> Unit)? = null
    var onDisconnectRequested: (() -> Unit)? = null
    var onMuteRequested: ((Boolean) -> Unit)? = null
    var onSpeakerRequested: ((Boolean) -> Unit)? = null

    private var isUserTerminated = false
    private val managerScope = CoroutineScope(Dispatchers.Main)

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            _callState.value = state
            updateTimes(state)
            updateBusyState()
        }
    }

    private fun updateBusyState() {
        val hasRealCall = _currentCall.value != null
        val hasSimCall = _isSimulatedCallActive.value
        val isActive = hasRealCall || hasSimCall
        
        _isBusy.value = isActive
        _busyMessage.value = when {
            _isRealCall.value -> "A real call is currently happening. Try again later."
            hasSimCall -> "There is already an ongoing Simulated call. Try again later."
            else -> null
        }
    }

    private fun updateTimes(state: Int) {
        if (_startTime.value == 0L) {
            _startTime.value = _customStartTime.value ?: System.currentTimeMillis()
        }
        if (state == Call.STATE_ACTIVE && _answerTime.value == 0L) {
            _answerTime.value = System.currentTimeMillis()
        }
    }

    // --- State Setters ---

    fun setCustomStartTime(timestamp: Long?) { _customStartTime.value = timestamp }
    fun setIntendedDuration(duration: Long?) { _intendedDuration.value = duration }
    fun setMimicSimHandle(handle: PhoneAccountHandle?) { _mimicSimHandle.value = handle }
    fun setCallFeatures(features: Int) { _callFeatures.value = features }

    fun onCallAdded(call: Call, context: Context, isSimulated: Boolean? = null) {
        _currentCall.value = call
        _callState.value = call.state
        
        val isSimulatedCall = isSimulated ?: _isSimulatedCallActive.value
        _isRealCall.value = !isSimulatedCall
        
        updateTimes(call.state)
        updateBusyState()
        call.registerCallback(callCallback)
    }

    fun onCallRemoved(call: Call) {
        if (_currentCall.value == call) {
            call.unregisterCallback(callCallback)
            _currentCall.value = null
            _callState.value = Call.STATE_DISCONNECTED
            _isRealCall.value = false
            updateBusyState()
        }
    }

    fun setSimulatedCallActive(context: Context, active: Boolean, phoneNumber: String? = null, state: Int = Call.STATE_ACTIVE, type: Int? = null) {
        _isSimulatedCallActive.value = active
        if (phoneNumber != null) _simulatedPhoneNumber.value = phoneNumber
        
        if (active) {
            _callState.value = state
            updateTimes(state)
            if (type != null) _callType.value = type
        } else {
            _callState.value = Call.STATE_DISCONNECTED
        }
        updateBusyState()
    }

    // --- Termination & Logging ---

    /**
     * Records call end. Uses a launch with NonCancellable to ensure DB write finishes.
     */
    fun recordCallEnd(context: Context) {
        val number = _simulatedPhoneNumber.value ?: return
        val start = _startTime.value
        val answer = _answerTime.value
        val type = _callType.value
        val intended = _intendedDuration.value
        val simHandle = _mimicSimHandle.value
        val feats = _callFeatures.value
        
        managerScope.launch {
            recordCallEndInternal(context, number, start, answer, type, intended, simHandle, feats)
        }
    }

    suspend fun terminateSimulatedSession(
        context: Context,
        phoneNumber: String,
        startTime: Long,
        answerTime: Long,
        callType: Int,
        intendedDuration: Long? = null,
        simHandle: PhoneAccountHandle? = null,
        features: Int = 0
    ) {
        Log.d(TAG, "Atomic termination started for $phoneNumber")
        recordCallEndInternal(context, phoneNumber, startTime, answerTime, callType, intendedDuration, simHandle, features)
        forceClearState(context)
    }

    private suspend fun recordCallEndInternal(
        context: Context,
        phoneNumber: String,
        startTime: Long,
        answerTime: Long,
        callType: Int,
        intendedDuration: Long?,
        simHandle: PhoneAccountHandle?,
        features: Int
    ) = withContext(Dispatchers.IO + NonCancellable) {
        Log.d(TAG, "Recording call log for $phoneNumber. Start: $startTime, Answer: $answerTime")

        val endTime = System.currentTimeMillis()
        var finalType = callType
        var finalDuration = 0L

        when {
            callType == CallLog.Calls.MISSED_TYPE -> {
                finalDuration = 0L
            }
            callType == CallLog.Calls.INCOMING_TYPE && answerTime == 0L -> {
                finalType = if (isUserTerminated) CallLog.Calls.REJECTED_TYPE else CallLog.Calls.MISSED_TYPE
                finalDuration = 0L
            }
            answerTime > 0L -> {
                val actualElapsed = (endTime - answerTime) / 1000
                finalDuration = if (isUserTerminated) actualElapsed else (intendedDuration ?: actualElapsed)
            }
            callType == CallLog.Calls.OUTGOING_TYPE && answerTime == 0L -> {
                finalDuration = 0L
            }
        }

        val helper = com.example.alibi.util.CallLogHelper.getInstance(context)
        helper.insertCallLog(phoneNumber, finalDuration, startTime, finalType, simHandle, features)
        Log.d(TAG, "Call log inserted successfully: $phoneNumber, dur: $finalDuration")
    }

    // --- UI Actions ---

    fun answer() {
        _currentCall.value?.answer(0)
        onAnswerRequested?.invoke()
    }

    fun disconnect() {
        isUserTerminated = true
        _currentCall.value?.disconnect()
        onDisconnectRequested?.invoke()
        
        _callState.value = Call.STATE_DISCONNECTED
        updateBusyState()
    }

    /**
     * Wipes all state. Called before new calls or on app reset.
     */
    fun forceClearState(context: Context) {
        _currentCall.value = null
        _callState.value = Call.STATE_DISCONNECTED
        _isSimulatedCallActive.value = false
        _isRealCall.value = false
        _simulatedPhoneNumber.value = null
        _startTime.value = 0
        _answerTime.value = 0
        _customStartTime.value = null
        _intendedDuration.value = null
        _mimicSimHandle.value = null
        _callFeatures.value = 0
        isUserTerminated = false
        onAnswerRequested = null
        onDisconnectRequested = null
        onSpeakerRequested = null
        onMuteRequested = null
        
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancel(101) 
        
        updateBusyState()
    }

    fun updateAudioState(muted: Boolean, speaker: Boolean) {
        _isMuted.value = muted
        _speakerOn.value = speaker
    }

    fun toggleMute() { onMuteRequested?.invoke(!_isMuted.value) }
    fun toggleSpeaker() { onSpeakerRequested?.invoke(!_speakerOn.value) }
}
