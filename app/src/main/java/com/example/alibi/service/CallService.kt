package com.example.alibi.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager

class CallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val isSimulated = call.details.accountHandle?.componentName?.packageName == packageName
        
        // CRITICAL: If this is a simulated call, ignore it here.
        // Simulated calls are handled exclusively via the CallControl API in TelecomHelper.
        // Managing them here too causes session deadlocks and ghost notifications.
        if (isSimulated) return

        CallStateManager.onCallAdded(call, this, false)
        
        updateNotification(call, false)
        
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                updateNotification(call, isSimulated)
            }
        })
        
        CallStateManager.onMuteRequested = { setMuted(it) }
        CallStateManager.onSpeakerRequested = { enabled ->
            @Suppress("DEPRECATION")
            setAudioRoute(if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
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
