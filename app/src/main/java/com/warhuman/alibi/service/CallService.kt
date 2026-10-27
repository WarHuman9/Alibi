package com.warhuman.alibi.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.core.content.ContextCompat
import com.warhuman.alibi.telecom.CallStateManager
import com.warhuman.alibi.telecom.TelecomConstants
import com.warhuman.alibi.util.getAlibiId
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

class CallService : InCallService() {
    
    private val registeredCallIds = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private lateinit var backgroundThread: android.os.HandlerThread
    private lateinit var backgroundHandler: android.os.Handler
    
    private lateinit var audioRouteManager: com.warhuman.alibi.util.CallAudioRouteManager
    private lateinit var audioPolicyManager: com.warhuman.alibi.telecom.AudioPolicyManager

    override fun onCreate() {
        super.onCreate()
        backgroundThread = android.os.HandlerThread(TelecomConstants.CALL_SERVICE_BG_THREAD)
        backgroundThread.start()
        backgroundHandler = android.os.Handler(backgroundThread.looper)
        
        audioRouteManager = com.warhuman.alibi.util.CallAudioRouteManager(this)
        audioPolicyManager = com.warhuman.alibi.telecom.AudioPolicyManager(this)
        
        Log.d("Alibi_CallService", "onCreate: Managers initialized")
    }

    override fun onDestroy() {
        super.onDestroy()
        CallStateManager.clearAudioHandlers(priority = true)
        audioPolicyManager.cleanup()
        backgroundThread.quitSafely()
        Log.d("Alibi_CallService", "onDestroy: Background thread stopped and managers cleaned up")
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
        
        val isSimulated = extras.containsKey(TelecomConstants.EXTRA_ALIBI_CALL_ID) || isSimulatedByHandle
        
        val id = call.getAlibiId()
        Log.d("Alibi_CallService", "Resolved Call ID: $id (isSimulated=$isSimulated, byHandle=$isSimulatedByHandle)")

        // Register with manager BEFORE triggering notification intent
        CallStateManager.onCallAdded(call, isSimulated, backgroundHandler)
        
        // Task 18: One-time notification start. 
        // FullScreenIntent on notification delegates activity launch exclusively to SystemUI
        updateNotification(call, isSimulated)

        CallStateManager.setAudioHandlers(
            mute = { audioRouteManager.setMuted(it) },
            speaker = { audioRouteManager.setSpeaker(it) },
            priority = true
        )

        // Single Hook for audio path and notification updates (if needed)
        CallStateManager.onCallStateChangedHook = { _, state ->
            if (state == Call.STATE_ACTIVE && !isSimulated) {
                audioRouteManager.scheduleEarpieceTransition()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val now = System.currentTimeMillis()
        val callHash = call.hashCode()
        if (!registeredCallIds.remove(callHash)) {
            Log.d("Alibi_CallService", "[$now] onCallRemoved: Call $callHash already removed. Skipping.")
            return 
        }
        
        val callId = CallStateManager.getCallId(call) ?: call.getAlibiId()
        Log.d("Alibi_CallService", "[$now] onCallRemoved: system signal for $callId (hash=$callHash)")
        
        // Clean up callbacks and remove from manager map
        CallStateManager.onCallRemoved(call)

        // Cleanup global listeners only if no more calls are active
        if (CallStateManager.totalActiveCalls.value == 0) {
            CallStateManager.clearAudioHandlers(priority = true)
            CallStateManager.onCallStateChangedHook = null
        }
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onCallAudioStateChanged(audioState: android.telecom.CallAudioState?) {
        // Update audio state for all calls to ensure UI is in sync
        audioState?.let {
            CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun updateNotification(call: Call, isSimulated: Boolean) {
        val details = call.details
        val state = if (Build.VERSION.SDK_INT >= 31) {
            details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }
        
        val id = call.getAlibiId()
        
        // Fast Metadata Extraction: Extract directly from Call object to avoid race conditions with StateFlow.
        val rawNumber = details.handle?.schemeSpecificPart?.ifBlank { null }
        val phoneNumber = rawNumber ?: "Unknown"
        val name = details.callerDisplayName?.ifBlank { null } ?: phoneNumber
        
        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(TelecomConstants.EXTRA_CALL_ID, id)
            putExtra(TelecomConstants.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(TelecomConstants.EXTRA_NAME, name)
            putExtra(TelecomConstants.EXTRA_IS_INCOMING, state == Call.STATE_RINGING)
            putExtra(TelecomConstants.EXTRA_IS_DIALING, state == Call.STATE_DIALING || state == Call.STATE_CONNECTING)
            putExtra(TelecomConstants.EXTRA_IS_SIMULATED, isSimulated)
            
            // For real calls, we use the system's connect time if active.
            val connectTime = if (state == Call.STATE_ACTIVE) details.connectTimeMillis else 0L
            if (connectTime > 0L) {
                putExtra(TelecomConstants.EXTRA_START_TIME, connectTime)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
