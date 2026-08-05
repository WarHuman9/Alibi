package com.example.alibi.telecom

import android.telecom.Call
import android.provider.CallLog
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object CallStateManager {
    private val _currentCall = MutableStateFlow<Call?>(null)
    val currentCall: StateFlow<Call?> = _currentCall.asStateFlow()

    private val _callState = MutableStateFlow(Call.STATE_DISCONNECTED)
    val callState: StateFlow<Int> = _callState.asStateFlow()

    private val _isSimulatedCallActive = MutableStateFlow(value = false)
    val isSimulatedCallActive: StateFlow<Boolean> = _isSimulatedCallActive.asStateFlow()

    private val _isRealCall = MutableStateFlow(value = false)
    val isRealCall: StateFlow<Boolean> = _isRealCall.asStateFlow()

    private val _simulatedPhoneNumber = MutableStateFlow<String?>(null)
    val simulatedPhoneNumber: StateFlow<String?> = _simulatedPhoneNumber.asStateFlow()

    private val _startTime = MutableStateFlow(0L)

    private val _customStartTime = MutableStateFlow<Long?>(null)

    private val _intendedDuration = MutableStateFlow<Long?>(null)

    private val _mimicSimHandle = MutableStateFlow<android.telecom.PhoneAccountHandle?>(null)

    private val _callFeatures = MutableStateFlow(0)

    private val _isMuted = MutableStateFlow(value = false)
    val isMuted: StateFlow<Boolean> = _isMuted.asStateFlow()

    private val _speakerOn = MutableStateFlow(value = false)
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    private val _isBusy = MutableStateFlow(value = false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _busyMessage = MutableStateFlow<String?>(null)
    val busyMessage: StateFlow<String?> = _busyMessage.asStateFlow()

    private val _answerTime = MutableStateFlow(0L)
    val answerTime: StateFlow<Long> = _answerTime.asStateFlow()

    private val _callType = MutableStateFlow(CallLog.Calls.INCOMING_TYPE)
    val logCallType: StateFlow<Int> = _callType.asStateFlow()

    var onDisconnectRequested: (() -> Unit)? = null
    private var isUserTerminated = false

    private fun persistSession(context: android.content.Context) {
        context.getSharedPreferences("active_simulation", android.content.Context.MODE_PRIVATE).edit {
            putString("num", _simulatedPhoneNumber.value)
            putLong("start", _startTime.value)
            putLong("custom_start", _customStartTime.value ?: -1L)
            putLong("intended_dur", _intendedDuration.value ?: -1L)
            putInt("type", _callType.value)
            putInt("feats", _callFeatures.value)
            putString("sim_id", _mimicSimHandle.value?.id)
            putBoolean("active", _isSimulatedCallActive.value)
        }
    }

    private fun clearPersistedSession(context: android.content.Context) {
        context.getSharedPreferences("active_simulation", android.content.Context.MODE_PRIVATE).edit {
            clear()
        }
    }

    fun restoreState(context: android.content.Context) {
        val prefs = context.getSharedPreferences("active_simulation", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("active", false)) {
            _simulatedPhoneNumber.value = prefs.getString("num", null)
            _startTime.value = prefs.getLong("start", 0L)
            val custom = prefs.getLong("custom_start", -1L)
            _customStartTime.value = if (custom != -1L) custom else null
            val intended = prefs.getLong("intended_dur", -1L)
            _intendedDuration.value = if (intended != -1L) intended else null
            _callType.value = prefs.getInt("type", CallLog.Calls.INCOMING_TYPE)
            _callFeatures.value = prefs.getInt("feats", 0)
            _isSimulatedCallActive.value = true
        }
    }

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            _callState.value = state
            updateTimes(state)
            updateBusyState()
        }
    }

    private fun updateBusyState() {
        val currentCallState = _currentCall.value?.let { 
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                it.details.state
            } else {
                @Suppress("DEPRECATION")
                it.state
            }
        } ?: Call.STATE_DISCONNECTED
        val simulatedActive = _isSimulatedCallActive.value
        
        val isActive = (currentCallState != Call.STATE_DISCONNECTED) || simulatedActive
        _isBusy.value = isActive
        
        if (isActive) {
            _busyMessage.value = if (_isRealCall.value) {
                "A real call is currently happening. Try again later."
            } else {
                "There is already an ongoing Simulated call. Try again later."
            }
        } else {
            _busyMessage.value = null
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

    fun setCustomStartTime(timestamp: Long?) {
        _customStartTime.value = timestamp
    }

    fun setIntendedDuration(duration: Long?) {
        _intendedDuration.value = duration
    }

    fun setMimicSimHandle(handle: android.telecom.PhoneAccountHandle?) {
        _mimicSimHandle.value = handle
    }

    fun setCallFeatures(features: Int) {
        _callFeatures.value = features
    }

    fun onCallAdded(call: Call, context: android.content.Context, isSimulated: Boolean? = null) {
        _currentCall.value = call
        val state = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        _callState.value = state
        
        // Detect if this is our own simulated call or a real network call
        val isSimulatedCall = isSimulated ?: _isSimulatedCallActive.value
        _isRealCall.value = !isSimulatedCall
        
        if (!isSimulatedCall) {
            // Real call pre-emption logic
            preemptSimulatedCall(context)
        }
        
        updateTimes(state)
        updateBusyState()
        call.registerCallback(callCallback)
    }

    fun onCallRemoved(call: Call) {
        if (_currentCall.value == call) {
            call.unregisterCallback(callCallback)
            _currentCall.value = null
            _callState.value = Call.STATE_DISCONNECTED
            
            // If it was a simulated call, we might need to cleanup
            if (_isSimulatedCallActive.value) {
                _isSimulatedCallActive.value = false
            }
            
            _isRealCall.value = false
            updateBusyState()
            
            // Reset simulation data immediately to prevent duplicate logging
            _simulatedPhoneNumber.value = null
            _startTime.value = 0
            _answerTime.value = 0
            _customStartTime.value = null
            _intendedDuration.value = null
        }
    }

    fun preemptSimulatedCall(context: android.content.Context) {
        if (_isSimulatedCallActive.value) {
            isUserTerminated = false // Instant pre-emption isn't user termination in the manual sense
            onDisconnectRequested?.invoke()
            recordCallEndInternal(context) // Save what we can
            _isSimulatedCallActive.value = false
            updateBusyState()
        }
    }

    fun setSimulatedCallActive(context: android.content.Context, active: Boolean, phoneNumber: String? = null, state: Int = Call.STATE_ACTIVE, type: Int? = null) {
        _isSimulatedCallActive.value = active
        if (phoneNumber != null) {
            _simulatedPhoneNumber.value = phoneNumber
        }
        if (active) {
            _callState.value = state
            updateTimes(state)
            if (type != null) {
                _callType.value = type
            }
            persistSession(context)
        } else {
            _callState.value = Call.STATE_DISCONNECTED
            clearPersistedSession(context)
        }
        updateBusyState()
    }

    fun recordCallEnd(context: android.content.Context) {
        recordCallEndInternal(context)
    }

    private fun recordCallEndInternal(context: android.content.Context? = null) {
        val phoneNumber = _simulatedPhoneNumber.value ?: return
        val start = _startTime.value
        val answer = _answerTime.value
        val intended = _intendedDuration.value
        val simHandle = _mimicSimHandle.value
        val features = _callFeatures.value

        // Only log if we have a number AND it was explicitly a simulated call
        if (_isSimulatedCallActive.value && (start > 0 || _customStartTime.value != null)) {
            val endTime = System.currentTimeMillis()
            val finalStartTime = _customStartTime.value ?: start
            
            // Logic for Duration & Call Type
            var finalType = _callType.value
            var finalDuration = 0L

            if (_callType.value == CallLog.Calls.MISSED_TYPE) {
                finalDuration = 0L
            } else if (_callType.value == CallLog.Calls.INCOMING_TYPE && answer == 0L) {
                // Was ringing but never answered
                finalType = if (isUserTerminated) CallLog.Calls.REJECTED_TYPE else CallLog.Calls.MISSED_TYPE
                finalDuration = 0L
            } else if (answer > 0L) {
                // Call was active
                val actualElapsed = (endTime - answer) / 1000
                // Use actual elapsed if it was user-terminated, otherwise use intended
                finalDuration = if (isUserTerminated) actualElapsed else (intended ?: actualElapsed)
            } else if (_callType.value == CallLog.Calls.OUTGOING_TYPE && answer == 0L) {
                // Outgoing never answered
                finalDuration = 0L
            }

            context?.let {
                val helper = com.example.alibi.util.CallLogHelper.getInstance(it)
                helper.insertCallLog(phoneNumber, finalDuration, finalStartTime, finalType, simHandle, features)
            }
            
            // Reset everything
            _startTime.value = 0
            _answerTime.value = 0
            _customStartTime.value = null
            _intendedDuration.value = null
            _mimicSimHandle.value = null
            _callFeatures.value = 0
            isUserTerminated = false
        }
    }

    fun answer() {
        _currentCall.value?.answer(0)
        onAnswerRequested?.invoke()
    }

    var onAnswerRequested: (() -> Unit)? = null

    fun disconnect() {
        isUserTerminated = true
        _currentCall.value?.disconnect()
        onDisconnectRequested?.invoke()
        _isSimulatedCallActive.value = false
        _callState.value = Call.STATE_DISCONNECTED
    }

    fun toggleMute() {
        val newMute = !_isMuted.value
        onMuteRequested?.invoke(newMute)
    }

    fun toggleSpeaker() {
        val newSpeaker = !_speakerOn.value
        onSpeakerRequested?.invoke(newSpeaker)
    }

    var onMuteRequested: ((Boolean) -> Unit)? = null
    var onSpeakerRequested: ((Boolean) -> Unit)? = null

    fun updateAudioState(muted: Boolean, speaker: Boolean) {
        _isMuted.value = muted
        _speakerOn.value = speaker
    }
}
