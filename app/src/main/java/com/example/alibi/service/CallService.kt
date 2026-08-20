package com.example.alibi.service

import android.content.Intent
import android.os.Build
import android.telecom.Call
import android.telecom.InCallService
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

import com.example.alibi.util.CallAudioRouteManager
import com.example.alibi.util.CallUiManager
import com.example.alibi.util.getAlibiId

class CallService : InCallService() {
    
    private val callCallbacks = ConcurrentHashMap<Call, Call.Callback>()
    private val registeredCallIds = Collections.newSetFromMap(ConcurrentHashMap<Int, Boolean>())
    private lateinit var backgroundThread: android.os.HandlerThread
    private lateinit var backgroundHandler: android.os.Handler
    
    private lateinit var uiManager: CallUiManager
    private lateinit var audioRouteManager: CallAudioRouteManager

    override fun onCreate() {
        super.onCreate()
        backgroundThread = android.os.HandlerThread(TelecomConstants.CALL_SERVICE_BG_THREAD)
        backgroundThread.start()
        backgroundHandler = android.os.Handler(backgroundThread.looper)
        
        uiManager = CallUiManager(this)
        audioRouteManager = CallAudioRouteManager(this)
        
        Log.d("Alibi_CallService", "onCreate: Background thread and managers initialized")
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
        
        val isSimulated = extras.containsKey(TelecomConstants.EXTRA_ALIBI_CALL_ID) || isSimulatedByHandle
        
        val id = call.getAlibiId()
        Log.d("Alibi_CallService", "Resolved Call ID: $id (isSimulated=$isSimulated, byHandle=$isSimulatedByHandle)")

        // Register with manager BEFORE triggering notification intent
        CallStateManager.onCallAdded(call, isSimulated, backgroundHandler)
        
        // Task 18: One-time notification start. 
        updateNotification(call, isSimulated)

        if (!isSimulated) {
            uiManager.showRealCallUi(id)
        }

        CallStateManager.setAudioHandlers(
            mute = { audioRouteManager.setMuted(it) },
            speaker = { audioRouteManager.setSpeaker(it) },
            priority = true
        )

        // Single Hook for audio path and notification updates (if needed)
        CallStateManager.onCallStateChangedHook = { c, state ->
            if (state == Call.STATE_ACTIVE && !isSimulated) {
                audioRouteManager.scheduleEarpieceTransition()
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        val callHash = call.hashCode()
        if (!registeredCallIds.remove(callHash)) {
            Log.d("Alibi_CallService", "onCallRemoved: Call $callHash already removed or not found. Skipping redundant cleanup.")
            return 
        }
        Log.d("Alibi_CallService", "onCallRemoved: hash=$callHash")
        
        val callId = call.getAlibiId()
        
        uiManager.clearUiState(callId)
        Log.d("Alibi_CallService", "onCallRemoved: Removing call $callId immediately.")
        
        // Task 14: Immediate removal from manager map to prevent UI deadlock
        CallStateManager.removeCall(callId)
        
        // Still call the formal cleanup to unregister callbacks
        CallStateManager.onCallRemoved(call)

        // Cleanup global listeners only if no more calls are active
        if (CallStateManager.totalActiveCalls.value == 0) {
            CallStateManager.clearAudioHandlers(priority = true)
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
        
        val id = call.getAlibiId()
        val callInfo = CallStateManager.activeCalls.value[id]
        val phase = callInfo?.phase
        val isDialingPhase = phase == com.example.alibi.telecom.SimulationPhase.DIALING || 
                           phase == com.example.alibi.telecom.SimulationPhase.RINGING

        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(TelecomConstants.EXTRA_CALL_ID, id)
            putExtra(TelecomConstants.EXTRA_PHONE_NUMBER, call.details.handle?.schemeSpecificPart)
            val name = call.details.callerDisplayName ?: call.details.handle?.schemeSpecificPart ?: "Unknown"
            putExtra(TelecomConstants.EXTRA_NAME, name)
            putExtra(TelecomConstants.EXTRA_IS_INCOMING, state == Call.STATE_RINGING || (isSimulated && phase == com.example.alibi.telecom.SimulationPhase.RINGING))
            putExtra(TelecomConstants.EXTRA_IS_DIALING, state == Call.STATE_DIALING || state == Call.STATE_CONNECTING || (isSimulated && isDialingPhase))
            putExtra(TelecomConstants.EXTRA_IS_SIMULATED, isSimulated)
            
            val startTimeValue = callInfo?.answerTime ?: 0L
            if (startTimeValue > 0L) {
                putExtra(TelecomConstants.EXTRA_START_TIME, startTimeValue)
            } else if (state == Call.STATE_ACTIVE && !isDialingPhase) {
                 putExtra(TelecomConstants.EXTRA_START_TIME, System.currentTimeMillis())
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }
}
