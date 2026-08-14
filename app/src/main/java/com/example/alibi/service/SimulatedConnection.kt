package com.example.alibi.service

import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.util.Log
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Custom [Connection] for simulated calls.
 * Manages local metadata to ensure accurate logging even if global state is reset.
 */
class SimulatedConnection(private val context: Context, id: String? = null) : Connection() {

    val connectionId = id ?: java.util.UUID.randomUUID().toString()
    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isDestroyed = AtomicBoolean(false)
    private val isSimulationAnswered = AtomicBoolean(false)
    private var durationJob: Job? = null
    private var cloakingJob: Job? = null
    private var autoAnswerJob: Job? = null
    private var autoMissJob: Job? = null
    private var stateBeforeHold: Int = STATE_ACTIVE

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
        
        localStartTime = System.currentTimeMillis()

        // Task 15: Register and push initial state immediately
        CallStateManager.registerConnection(connectionId, this)
        CallStateManager.updateCallState(connectionId, Call.STATE_CONNECTING)

        CallStateManager.onDisconnectRequested = { 
            Log.d(TAG, "onDisconnectRequested callback triggered for $connectionId")
            onDisconnect() 
        }
        CallStateManager.onAnswerRequested = { 
            Log.d(TAG, "onAnswerRequested callback triggered for $connectionId")
            onAnswer() 
        }


        // Audio state listeners for self-managed simulation UI
        CallStateManager.setAudioHandlers(
            mute = { muted ->
                // Manually update manager as Connection lacks a direct setMuted() API.
                // This ensures the UI reflects the requested state immediately.
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

        // Set Connection reference for focus management
        AudioHeartbeatManager.getInstance(context).connection = this

        startCloakingWatchdog()
    }

    private fun startCloakingWatchdog() {
        if (Build.VERSION.SDK_INT >= 35) { // Android 15/16+
            cloakingJob = connectionScope.launch {
                // Cloak after 40 seconds to beat the system inactivity watchdog reliably (API 35+)
                delay(40.seconds)
                if (state == STATE_DIALING || state == STATE_RINGING) {
                    Log.i(TAG, "Watchdog: Cloaking call state to ACTIVE to avoid system kill (API 35+).")
                    setActive()
                    // Mute via manager to ensure system-level silence while UI says "Ringing/Calling"
                    CallStateManager.onMuteRequested?.invoke(true)
                }
            }
        }
    }

    fun setMetadata(
        number: String?,
        startTime: Long,
        type: Int,
        duration: Long?,
        sim: PhoneAccountHandle?,
        features: Int
    ) {
        localPhoneNumber = number
        localStartTime = startTime
        localCallType = type
        localIntendedDuration = duration
        localMimicSimHandle = sim
        localCallFeatures = features

        // Start Heartbeat for Outgoing calls immediately to maintain priority
        if (type == android.provider.CallLog.Calls.OUTGOING_TYPE) {
            AudioHeartbeatManager.getInstance(context).start()
        }

        updateNotification()
    }

    fun setAutoAnswerDelay(seconds: Int) {
        if (seconds < 0) return
        autoAnswerJob?.cancel()
        autoAnswerJob = connectionScope.launch {
            delay(if (seconds > 0) seconds.seconds else 500.milliseconds)
            if (!isSimulationAnswered.get() && state != STATE_DISCONNECTED) {
                Log.d(TAG, "Auto-answering call after delay: $seconds s")
                onAnswer()
            }
        }
    }

    fun setAutoMissDelay(seconds: Int) {
        if (seconds <= 0) return
        autoMissJob?.cancel()
        autoMissJob = connectionScope.launch {
            delay(seconds.seconds)
            if (!isSimulationAnswered.get() && state != STATE_DISCONNECTED) {
                Log.d(TAG, "Auto-missing call after delay: $seconds s")
                onReject()
            }
        }
    }

    override fun onAnswer() {
        Log.d(TAG, "onAnswer requested. Current system state: $state")
        val wasCloaked = state == STATE_ACTIVE
        
        if (isSimulationAnswered.getAndSet(true)) {
            Log.d(TAG, "Call already answered. Ignoring redundant request.")
            return
        }
        
        localAnswerTime = System.currentTimeMillis()

        // Cancel all watchdog and transition timers

        cloakingJob?.cancel()
        cloakingJob = null
        autoAnswerJob?.cancel()
        autoAnswerJob = null
        autoMissJob?.cancel()
        autoMissJob = null

        if (!wasCloaked) {
            setActive()
        } else {
            Log.i(TAG, "Call was cloaked. Transitioning UI to ACTIVE and unmuting.")
            // Ensure audio is restored if we were cloaked-muted
            CallStateManager.onMuteRequested?.invoke(false)
        }
        
        CallStateManager.updateCallState(connectionId, Call.STATE_ACTIVE)

        // Start heartbeat for answered incoming calls
        AudioHeartbeatManager.getInstance(context).start()
        
        localAnswerTime = System.currentTimeMillis()
        
        // Start automatic hang-up timer if duration is set and positive
        localIntendedDuration?.takeIf { it > 0 }?.let { duration ->
            durationJob?.cancel()
            durationJob = connectionScope.launch {
                Log.d(TAG, "Starting automatic hang-up timer: $duration seconds")
                delay(duration.seconds)
                Log.d(TAG, "Intended duration reached. Automatically hanging up.")
                onDisconnect()
            }
        }

        CallStateManager.setSimulatedCallActive(true, address?.schemeSpecificPart, id = connectionId)
        
        updateNotification()
    }

    override fun onReject() {
        Log.d(TAG, "onReject")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        CallStateManager.updateCallState(connectionId, Call.STATE_DISCONNECTED)
        cleanup()
    }

    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        CallStateManager.updateCallState(connectionId, Call.STATE_DISCONNECTED)
        cleanup()
    }

    override fun onAbort() {
        Log.d(TAG, "onAbort")
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        CallStateManager.updateCallState(connectionId, Call.STATE_DISCONNECTED)
        cleanup()
    }

    private fun cleanup() {
        if (isDestroyed.getAndSet(true)) return
        
        Log.d(TAG, "Cleanup initiated for connection: $connectionId")

        // Task 11/12: Capture snapshot using LOCAL properties for atomic accuracy
        val snapshot = CallStateManager.CallMetadata(
            number = localPhoneNumber ?: address?.schemeSpecificPart ?: "Unknown",
            type = localCallType,
            startTime = if (localStartTime > 0) localStartTime else System.currentTimeMillis(),
            answerTime = localAnswerTime,
            endTime = System.currentTimeMillis(),
            simHandle = localMimicSimHandle,
            features = localCallFeatures
        )

        // 1. Cancel all ongoing timers immediately

        durationJob?.cancel()
        durationJob = null
        cloakingJob?.cancel()
        cloakingJob = null
        autoAnswerJob?.cancel()
        autoAnswerJob = null
        autoMissJob?.cancel()
        autoMissJob = null
        
        // 2. Audio and Heartbeat stop immediately
        AudioHeartbeatManager.getInstance(context).connection = null
        AudioHeartbeatManager.getInstance(context).stop()

        // 3. Unregister from manager (context passed to handle service stop if last)
        CallStateManager.unregisterConnection(connectionId, context)

        // 4. Detach listeners
        if (CallStateManager.onDisconnectRequested?.let { it.javaClass.enclosingClass == this.javaClass } == true) {
            CallStateManager.onDisconnectRequested = null
        }
        if (CallStateManager.onAnswerRequested?.let { it.javaClass.enclosingClass == this.javaClass } == true) {
            CallStateManager.onAnswerRequested = null
        }
        // Only clear audio handlers if they were set by a simulated connection (non-priority)
        CallStateManager.clearAudioHandlers(priority = false) 

        // 5. Logging and Final Teardown
        // We use a non-connection-scoped job to ensure logging completes even after connectionScope is cancelled
        CoroutineScope(Dispatchers.IO).launch {
            try {
                CallStateManager.terminateSimulatedSession(
                    context = context,
                    snapshot = snapshot,
                    intendedDuration = localIntendedDuration
                )
            } finally {
                destroy()
            }
        }


        // Immediately cancel the connection scope as requested
        connectionScope.cancel()
    }

    override fun onHold() {
        if (state == STATE_HOLDING) return
        Log.d(TAG, "onHold received. Current state: $state")
        
        stateBeforeHold = state
        setOnHold() // Transition Telecom state
        CallStateManager.updateCallState(connectionId, Call.STATE_HOLDING)
        
        AudioHeartbeatManager.getInstance(context).stop()
        
        // Task 11/12: Centralized hold state update
        CallStateManager.setIsHolding(true, connectionId)
        CallStateManager.setSimulatedCallActive(true, address?.schemeSpecificPart, state = android.telecom.Call.STATE_HOLDING, id = connectionId)

        updateNotification()
    }

    override fun onUnhold() {
        if (state != STATE_HOLDING) return
        Log.d(TAG, "onUnhold received. Restoring to: $stateBeforeHold")
        
        val telecomState = when (stateBeforeHold) {
            STATE_RINGING -> {
                setRinging()
                Call.STATE_RINGING
            }
            STATE_DIALING -> {
                setDialing()
                Call.STATE_DIALING
            }
            else -> {
                setActive()
                Call.STATE_ACTIVE
            }
        }
        
        CallStateManager.updateCallState(connectionId, telecomState)
        
        AudioHeartbeatManager.getInstance(context).start()
        
        // Task 11/12: Centralized hold state update
        CallStateManager.setIsHolding(false, connectionId)
        CallStateManager.setSimulatedCallActive(true, address?.schemeSpecificPart, state = telecomState, id = connectionId)

        updateNotification()
    }

    
    fun setUnhold() = onUnhold()

    override fun onMuteStateChanged(isMuted: Boolean) {
        Log.d(TAG, "onMuteStateChanged: $isMuted")
        CallStateManager.updateAudioState(isMuted, CallStateManager.isSpeakerOn.value)
    }

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: android.telecom.CallAudioState?) {
        state?.let {
            Log.d(TAG, "onCallAudioStateChanged: muted=${it.isMuted}, route=${it.route}")
            CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
    }

    private fun updateNotification() {
        val phase = CallStateManager.activeCalls.value[connectionId]?.phase
        val isDialingPhase = phase == com.example.alibi.telecom.SimulationPhase.DIALING || 
                           phase == com.example.alibi.telecom.SimulationPhase.RINGING

        Log.d(TAG, "updateNotification: connectionId=$connectionId, state=$state, phase=$phase")

        val intent = Intent(context, CallNotificationService::class.java).apply {
            putExtra(CallNotificationService.EXTRA_CALL_ID, connectionId)
            putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, localPhoneNumber ?: address?.schemeSpecificPart)
            putExtra(CallNotificationService.EXTRA_IS_INCOMING, state == STATE_RINGING || phase == com.example.alibi.telecom.SimulationPhase.RINGING)
            putExtra(CallNotificationService.EXTRA_IS_DIALING, state == STATE_DIALING || state == STATE_INITIALIZING || isDialingPhase)
            putExtra(CallNotificationService.EXTRA_IS_SIMULATED, true)
            
            if (localAnswerTime > 0L) {
                putExtra(CallNotificationService.EXTRA_START_TIME, localAnswerTime)
            } else if (state == STATE_ACTIVE && !isDialingPhase) {
                putExtra(CallNotificationService.EXTRA_START_TIME, System.currentTimeMillis())
            }
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
