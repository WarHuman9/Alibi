package com.example.alibi.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallLogSnapshot
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulatedCallRequest
import com.example.alibi.telecom.TelecomConstants
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Custom [Connection] for simulated calls.
 * Task 18: Refactored to delegate logic to SimulationController.
 */
class SimulatedConnection(
    val context: Context,
    val request: SimulatedCallRequest
) : Connection() {

    val connectionId = request.alibiId
    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isDestroyed = AtomicBoolean(false)

    // Captured metadata for atomic logging
    private var localPhoneNumber: String? = null
    private var localStartTime: Long = 0L
    private var localAnswerTime: Long = 0L
    private var localCallType: Int = android.provider.CallLog.Calls.INCOMING_TYPE
    private var localIntendedDuration: Long? = null
    private var localMimicSimHandle: PhoneAccountHandle? = null
    private var localCallFeatures: Int = 0

    init {
        Log.d(TAG, "Initializing SimulatedConnection: $connectionId")
        connectionCapabilities = CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        audioModeIsVoip = true
        
        setInitializing()
        setAddress(android.net.Uri.fromParts("tel", request.phoneNumber, null), TelecomManager.PRESENTATION_ALLOWED)
        
        val ex = extras ?: Bundle()
        ex.putString(TelecomConstants.EXTRA_CONNECTION_ID, connectionId)
        ex.putString(TelecomConstants.EXTRA_ALIBI_CALL_ID, connectionId)
        setExtras(ex)

        localPhoneNumber = request.phoneNumber
        localStartTime = request.startTime ?: System.currentTimeMillis()
        localCallType = request.direction
        localIntendedDuration = request.duration
        localMimicSimHandle = request.simHandle
        localCallFeatures = request.features

        CallStateManager.registerConnection(connectionId, this)
        CallStateManager.setCustomStartTime(request.startTime)
        CallStateManager.setIntendedDuration(request.duration)
        CallStateManager.setMimicSimHandle(request.simHandle)
        CallStateManager.setCallFeatures(request.features)
        
        val initialState = if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) 
            Call.STATE_DIALING else Call.STATE_RINGING
            
        CallStateManager.setSimulatedCallActive(
            active = true,
            phoneNumber = request.phoneNumber,
            state = initialState,
            type = request.direction,
            id = connectionId
        )

        if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) {
            setDialing()
            AudioHeartbeatManager.getInstance(context).start()
        } else {
            setRinging()
        }

        SimulationController.registerConnection(connectionId, this)
        updateNotification()

        CallStateManager.setAudioHandlers(
            mute = { muted ->
                CallStateManager.updateAudioState(muted, CallStateManager.isSpeakerOn.value)
            },
            speaker = { speakerOn ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val route = if (speakerOn) android.telecom.CallAudioState.ROUTE_SPEAKER else android.telecom.CallAudioState.ROUTE_EARPIECE
                    @Suppress("DEPRECATION")
                    setAudioRoute(route)
                }
            },
            priority = false
        )

        AudioHeartbeatManager.getInstance(context).connection = this
    }

    override fun onAnswer() {
        Log.d(TAG, "onAnswer requested for $connectionId")
        localAnswerTime = System.currentTimeMillis()
        SimulationController.answerSimulatedCall(connectionId)
        updateNotification()
    }

    override fun onReject() {
        Log.d(TAG, "onReject for $connectionId")
        terminate(userInitiated = true, cause = DisconnectCause.REJECTED)
    }

    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect for $connectionId")
        terminate(userInitiated = true, cause = DisconnectCause.LOCAL)
    }

    override fun onAbort() {
        Log.d(TAG, "onAbort for $connectionId")
        terminate(userInitiated = true, cause = DisconnectCause.CANCELED)
    }

    override fun onHold() {
        Log.d(TAG, "onHold received for $connectionId")
        SimulationController.holdSimulatedCall(connectionId, true)
        updateNotification()
    }

    override fun onUnhold() {
        Log.d(TAG, "onUnhold received for $connectionId")
        SimulationController.holdSimulatedCall(connectionId, false)
        updateNotification()
    }

    fun setUnhold() = onUnhold()

    fun terminate(userInitiated: Boolean, cause: Int = DisconnectCause.LOCAL) {
        if (isDestroyed.get()) return
        Log.d(TAG, "terminate: $connectionId, userInitiated=$userInitiated, cause=$cause")
        setDisconnected(DisconnectCause(cause))
        CallStateManager.updateCallState(connectionId, Call.STATE_DISCONNECTED)
        cleanup(userInitiated)
    }

    private fun cleanup(isUserTerminated: Boolean) {
        if (isDestroyed.getAndSet(true)) return
        
        Log.d(TAG, "Cleanup initiated for connection: $connectionId")

        val snapshot = CallLogSnapshot(
            number = localPhoneNumber ?: address?.schemeSpecificPart ?: "Unknown",
            type = localCallType,
            startTime = if (localStartTime > 0) localStartTime else System.currentTimeMillis(),
            answerTime = localAnswerTime,
            endTime = System.currentTimeMillis(),
            simHandle = localMimicSimHandle,
            features = localCallFeatures,
            isSimulated = true
        )

        SimulationController.unregisterConnection(connectionId)
        
        AudioHeartbeatManager.getInstance(context).connection = null
        AudioHeartbeatManager.getInstance(context).stop()

        CallStateManager.unregisterConnection(connectionId, context)
        CallStateManager.clearAudioHandlers(priority = false) 

        CoroutineScope(Dispatchers.IO).launch {
            try {
                SimulationController.recordCallEnd(
                    context = context,
                    id = connectionId,
                    fallbackSnapshot = snapshot,
                    intendedDuration = localIntendedDuration,
                    isUserTerminated = isUserTerminated
                )
            } finally {
                destroy()
            }
        }
        connectionScope.cancel()
    }

    override fun onMuteStateChanged(isMuted: Boolean) {
        CallStateManager.updateAudioState(isMuted, CallStateManager.isSpeakerOn.value)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: android.telecom.CallAudioState?) {
        state?.let {
            CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun updateNotification() {
        val calls = CallStateManager.state.value.activeCalls
        val metadata = calls[connectionId]
        val phase = metadata?.phase
        val isDialingPhase = phase == com.example.alibi.telecom.SimulationPhase.DIALING || 
                           phase == com.example.alibi.telecom.SimulationPhase.RINGING

        val managerAnswerTime = metadata?.answerTime ?: 0L
        val finalStartTime = if (managerAnswerTime > 0L) managerAnswerTime else if (localAnswerTime > 0L) localAnswerTime else 0L

        val intent = Intent(context, CallNotificationService::class.java).apply {
            putExtra(TelecomConstants.EXTRA_CALL_ID, connectionId)
            putExtra(TelecomConstants.EXTRA_PHONE_NUMBER, localPhoneNumber ?: address?.schemeSpecificPart)
            putExtra(TelecomConstants.EXTRA_IS_INCOMING, state == STATE_RINGING || phase == com.example.alibi.telecom.SimulationPhase.RINGING)
            putExtra(TelecomConstants.EXTRA_IS_DIALING, state == STATE_DIALING || state == STATE_INITIALIZING || isDialingPhase)
            putExtra(TelecomConstants.EXTRA_IS_SIMULATED, true)
            if (finalStartTime > 0L) putExtra(TelecomConstants.EXTRA_START_TIME, finalStartTime)
        }
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start CallNotificationService", e)
        }
    }

    companion object {
        private const val TAG = "SimulatedConnection"
    }
}
