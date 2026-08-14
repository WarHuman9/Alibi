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
    private val launchedUiForCalls = mutableSetOf<String>()
    private val registeredCallIds = mutableSetOf<Int>()
    private lateinit var backgroundThread: android.os.HandlerThread
    private lateinit var backgroundHandler: android.os.Handler

    override fun onCreate() {
        super.onCreate()
        backgroundThread = android.os.HandlerThread("Alibi_CallService_Bg")
        backgroundThread.start()
        backgroundHandler = android.os.Handler(backgroundThread.looper)
        Log.d("Alibi_CallService", "onCreate: Background thread started")
    }

    override fun onDestroy() {
        super.onDestroy()
        CallStateManager.clearAudioHandlers(priority = true)
        backgroundThread.quitSafely()
        Log.d("Alibi_CallService", "onDestroy: Background thread stopped")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        
        val callHash = call.hashCode()
        if (registeredCallIds.contains(callHash)) {
            Log.d("Alibi_CallService", "onCallAdded: Call $callHash already registered. Skipping redundant setup.")
            return
        }
        registeredCallIds.add(callHash)
        
        Log.d("Alibi_CallService", "onCallAdded: hash=$callHash")
        val details = call.details
        val extras = details.extras ?: android.os.Bundle.EMPTY
        
        val accountHandle = details.accountHandle
        val isSimulatedByHandle = accountHandle?.componentName?.className?.contains("SimulatedConnectionService") == true
        
        val alibiId = extras.getString(com.example.alibi.telecom.TelecomConstants.EXTRA_ALIBI_CALL_ID)
        val isSimulated = alibiId != null || isSimulatedByHandle
        
        val id = alibiId ?: call.hashCode().toString()
        Log.d("Alibi_CallService", "Resolved Call ID: $id (isSimulated=$isSimulated, byHandle=$isSimulatedByHandle)")

        // Register with manager BEFORE triggering notification intent
        // Using backgroundHandler for the callback registration to keep Main thread responsive
        CallStateManager.onCallAdded(call, isSimulated, backgroundHandler)
        
        // Task 18: One-time notification start. 
        // Subsequent updates are handled by CallNotificationService observing the state flow.
        updateNotification(call, isSimulated)

        if (!isSimulated && !launchedUiForCalls.contains(id)) {
            Log.d("Alibi_CallService", "Real call detected. Launching MainActivity selective UI for $id")
            launchedUiForCalls.add(id)
            val uiIntent = Intent(this, com.example.alibi.MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("EXTRA_REAL_CALL", true)
            }
            
            val pendingIntent = android.app.PendingIntent.getActivity(
                this, 0, uiIntent, 
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            try {
                Log.d("Alibi_CallService", "Sending PendingIntent for real call UI.")
                pendingIntent.send()
            } catch (e: Exception) {
                Log.e("Alibi_CallService", "Failed to send PendingIntent, falling back to startActivity", e)
                startActivity(uiIntent)
            }
        }

        CallStateManager.setAudioHandlers(
            mute = { setMuted(it) },
            speaker = { enabled ->
                @Suppress("DEPRECATION")
                setAudioRoute(if (enabled) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE)
            },
            priority = true
        )

        // Single Hook for audio path and notification updates (if needed)
        CallStateManager.onCallStateChangedHook = { c, state ->
            if (state == Call.STATE_ACTIVE && !isSimulated) {
                Log.d("Alibi_CallService", "Real call ACTIVE. Scheduling audio route to EARPIECE (200ms delay).")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        @Suppress("DEPRECATION")
                        setAudioRoute(android.telecom.CallAudioState.ROUTE_EARPIECE)
                        Log.d("Alibi_CallService", "Delayed audio route set to EARPIECE.")
                    } catch (e: Exception) {
                        Log.e("Alibi_CallService", "Failed to set audio route in delayed handler", e)
                    }
                }, 200)
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val callHash = call.hashCode()
        if (!registeredCallIds.contains(callHash)) {
            return // Skip redundant cleanup and logging
        }
        registeredCallIds.remove(callHash)
        Log.d("Alibi_CallService", "onCallRemoved: hash=$callHash")
        
        val extras = call.details.extras ?: android.os.Bundle.EMPTY
        val alibiId = extras.getString(com.example.alibi.telecom.TelecomConstants.EXTRA_ALIBI_CALL_ID)
        val connectionId = extras.getString(com.example.alibi.telecom.TelecomConstants.EXTRA_CONNECTION_ID)
        val callId = alibiId ?: connectionId ?: call.hashCode().toString()
        
        launchedUiForCalls.remove(callId)
        Log.d("Alibi_CallService", "onCallRemoved: Removing call $callId immediately.")
        
        // Task 14: Immediate removal from manager map to prevent UI deadlock
        CallStateManager.removeCall(callId)
        
        // Still call the formal cleanup to unregister callbacks
        CallStateManager.onCallRemoved(call)

        // Cleanup global listeners only if no more calls are active
        if (CallStateManager.totalActiveCalls.value == 0) {
            CallStateManager.clearAudioHandlers(priority = true)
            CallStateManager.onAnswerRequested = null
            CallStateManager.onDisconnectRequested = null
            CallStateManager.onCallStateChangedHook = null
        }
    }


    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState?) {
        // Update audio state for all calls to ensure UI is in sync
        audioState?.let {
            CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun updateNotification(call: Call, isSimulated: Boolean) {
        val state = if (Build.VERSION.SDK_INT >= 31) {
            call.details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        
        // For simulated calls, we might be cloaked. Check phase from manager.
        val connectionId = call.details.extras?.getString(com.example.alibi.telecom.TelecomConstants.EXTRA_CONNECTION_ID)
        val id = connectionId ?: call.hashCode().toString()
        val phase = CallStateManager.activeCalls.value[id]?.phase
        val isDialingPhase = phase == com.example.alibi.telecom.SimulationPhase.DIALING || 
                           phase == com.example.alibi.telecom.SimulationPhase.RINGING

        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(CallNotificationService.EXTRA_CALL_ID, id)
            putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, call.details.handle?.schemeSpecificPart)
            val name = call.details.callerDisplayName ?: call.details.handle?.schemeSpecificPart ?: "Unknown"
            putExtra(CallNotificationService.EXTRA_NAME, name)
            putExtra(CallNotificationService.EXTRA_IS_INCOMING, state == Call.STATE_RINGING || (isSimulated && phase == com.example.alibi.telecom.SimulationPhase.RINGING))
            putExtra(CallNotificationService.EXTRA_IS_DIALING, state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || (isSimulated && isDialingPhase))
            putExtra(CallNotificationService.EXTRA_IS_SIMULATED, isSimulated)
            
            val startTime = CallStateManager.activeCalls.value[id]?.answerTime ?: 0L
            if (startTime > 0L) {
                putExtra(CallNotificationService.EXTRA_START_TIME, startTime)
            } else if (state == Call.STATE_ACTIVE && !isDialingPhase) {
                 putExtra(CallNotificationService.EXTRA_START_TIME, System.currentTimeMillis())
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
