package com.example.alibi.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager

class CallService : InCallService() {
    
    private val callCallbacks = mutableMapOf<Call, Call.Callback>()

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        
        // Comprehensive check for simulation
        val accountHandle = call.details.accountHandle
        val isSimulatedByPackage = accountHandle?.componentName?.packageName == packageName
        val isSimulatedById = accountHandle?.id?.contains("AlibiSimulatedAccount") ?: false
        val isSimulated = isSimulatedByPackage || isSimulatedById
        
        if (isSimulated) {
            Log.d("CallService", "Simulated call detected. Registering state for UI sync.")
            CallStateManager.onCallAdded(call, true)
            return
        }

        CallStateManager.onCallAdded(call, false)
        
        updateNotification(call, false)
        
        val callback = object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                updateNotification(call, isSimulated)
            }
        }
        callCallbacks[call] = callback
        call.registerCallback(callback)
        
        CallStateManager.onMuteRequested = { setMuted(it) }
        CallStateManager.onSpeakerRequested = { enabled ->
            @Suppress("DEPRECATION")
            setAudioRoute(if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        
        callCallbacks.remove(call)?.let { callback ->
            call.unregisterCallback(callback)
        }

        // Only cleanup if this service was actually managing the call
        if (CallStateManager.currentCall.value == call) {
            CallStateManager.onCallRemoved(call)
            CallStateManager.onMuteRequested = null
            CallStateManager.onSpeakerRequested = null
            
            // Stop the notification service when the real call ends
            stopService(Intent(this, CallNotificationService::class.java))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState?) {
        // Only update if we are in a real call. 
        // Simulated calls collect their own audio state via CallControlScope.
        if (CallStateManager.isRealCall.value) {
            audioState?.let {
                CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
            }
        }
    }

    private fun updateNotification(call: Call, isSimulated: Boolean) {
        // Simulated calls manage their own notifications via SimulatedConnection
        if (isSimulated) return

        val state = if (Build.VERSION.SDK_INT >= 31) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        
        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, call.details.handle?.schemeSpecificPart)
            putExtra(CallNotificationService.EXTRA_IS_INCOMING, state == Call.STATE_RINGING)
            putExtra(CallNotificationService.EXTRA_IS_DIALING, state == Call.STATE_DIALING || state == Call.STATE_CONNECTING)
            putExtra(CallNotificationService.EXTRA_IS_SIMULATED, false)
            if (state == Call.STATE_ACTIVE) {
                // For real calls, we use current time as answer time if it just became active
                putExtra(CallNotificationService.EXTRA_START_TIME, System.currentTimeMillis())
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
