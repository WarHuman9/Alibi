package com.example.alibi.service

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager

class CallService : InCallService() {
    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        val isSimulated = call.details.accountHandle?.componentName?.packageName == packageName
        CallStateManager.onCallAdded(call, this, isSimulated)
        
        updateNotification(call, isSimulated)
        
        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(call: Call, state: Int) {
                updateNotification(call, isSimulated)
            }
        })
        
        CallStateManager.onMuteRequested = { setMuted(it) }
        CallStateManager.onSpeakerRequested = { enabled ->
            setAudioRoute(if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        CallStateManager.onCallRemoved(call)
        CallStateManager.onMuteRequested = null
        CallStateManager.onSpeakerRequested = null
        
        // Stop the notification service when the real call ends
        stopService(Intent(this, CallNotificationService::class.java))
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState?) {
        audioState?.let {
            CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun updateNotification(call: Call, isSimulated: Boolean) {
        // Simulated calls manage their own notifications via SimulatedConnection
        if (isSimulated) return

        val state = call.state
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
