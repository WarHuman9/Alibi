package com.example.alibi.service

import android.content.Context
import android.content.Intent
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.util.Log
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
class SimulatedConnection(private val context: Context) : Connection() {

    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isDestroyed = AtomicBoolean(false)
    private var durationJob: Job? = null

    // Captured metadata for atomic logging
    private var localPhoneNumber: String? = null
    private var localStartTime: Long = 0L
    private var localAnswerTime: Long = 0L
    private var localCallType: Int = android.provider.CallLog.Calls.INCOMING_TYPE
    private var localIntendedDuration: Long? = null
    private var localMimicSimHandle: PhoneAccountHandle? = null
    private var localCallFeatures: Int = 0

    init {
        connectionCapabilities = CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        audioModeIsVoip = true
        
        CallStateManager.onDisconnectRequested = { onDisconnect() }
        CallStateManager.onAnswerRequested = { onAnswer() }
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
    }

    fun setAutoAnswerDelay(seconds: Int) {
        if (seconds < 0) return
        connectionScope.launch {
            delay(if (seconds > 0) seconds.seconds else 500.milliseconds)
            if (state != STATE_ACTIVE && state != STATE_DISCONNECTED) {
                onAnswer()
            }
        }
    }

    fun setAutoMissDelay(seconds: Int) {
        if (seconds <= 0) return
        connectionScope.launch {
            delay(seconds.seconds)
            if (state == STATE_RINGING) {
                onReject()
            }
        }
    }

    override fun onAnswer() {
        Log.d(TAG, "onAnswer")
        if (state != STATE_ACTIVE) {
            setActive()
            localAnswerTime = System.currentTimeMillis()
            
            // Start automatic hang-up timer if duration is set
            localIntendedDuration?.takeIf { it > 0 }?.let { duration ->
                durationJob?.cancel()
                durationJob = connectionScope.launch {
                    Log.d(TAG, "Starting automatic hang-up timer: $duration seconds")
                    delay(duration.seconds)
                    Log.d(TAG, "Intended duration reached. Automatically hanging up.")
                    onDisconnect()
                }
            }

            CallStateManager.setSimulatedCallActive(context, true, address?.schemeSpecificPart)
            
            val intent = Intent(context, CallNotificationService::class.java).apply {
                putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, address?.schemeSpecificPart)
                putExtra(CallNotificationService.EXTRA_IS_INCOMING, false)
                putExtra(CallNotificationService.EXTRA_IS_MISSED, false)
                putExtra(CallNotificationService.EXTRA_IS_DIALING, false)
                putExtra(CallNotificationService.EXTRA_IS_SIMULATED, true)
                putExtra(CallNotificationService.EXTRA_START_TIME, localAnswerTime)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onReject() {
        Log.d(TAG, "onReject")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        cleanup()
    }

    override fun onDisconnect() {
        Log.d(TAG, "onDisconnect")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        cleanup()
    }

    override fun onAbort() {
        Log.d(TAG, "onAbort")
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        cleanup()
    }

    private fun cleanup() {
        if (isDestroyed.getAndSet(true)) return
        durationJob?.cancel()
        durationJob = null

        connectionScope.launch {
            try {
                // Ensure atomic log write before state wipe
                localPhoneNumber?.let { number ->
                    CallStateManager.terminateSimulatedSession(
                        context = context,
                        phoneNumber = number,
                        startTime = if (localStartTime > 0) localStartTime else System.currentTimeMillis(),
                        answerTime = localAnswerTime,
                        callType = localCallType,
                        intendedDuration = localIntendedDuration,
                        simHandle = localMimicSimHandle,
                        features = localCallFeatures
                    )
                } ?: CallStateManager.forceClearState(context)

                // Give logs a tiny breath to flush to disk/db
                delay(100.milliseconds)
            } finally {
                context.stopService(Intent(context, CallNotificationService::class.java))
                destroy()
                connectionScope.cancel()
            }
        }
    }

    override fun onHold() = setOnHold()
    override fun onUnhold() = setActive()

    @Deprecated("Deprecated in Java")
    override fun onCallAudioStateChanged(state: android.telecom.CallAudioState?) {
        state?.let {
            CallStateManager.updateAudioState(it.isMuted, it.route == android.telecom.CallAudioState.ROUTE_SPEAKER)
        }
    }

    companion object {
        private const val TAG = "SimulatedConnection"
    }
}
