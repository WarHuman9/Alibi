package com.example.alibi.service

import android.content.Context
import android.content.Intent
import android.telecom.Connection
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class SimulatedConnection(private val context: Context) : Connection() {

    private val connectionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoAnswerDelaySeconds: Int = 0

    init {
        connectionCapabilities = CAPABILITY_SUPPORT_HOLD or CAPABILITY_HOLD
        audioModeIsVoip = true
        setDialing() // Ensure initial state is DIALING for outgoing
        CallStateManager.onDisconnectRequested = { onDisconnect() }
        CallStateManager.onAnswerRequested = { onAnswer() }
    }

    fun setAutoAnswerDelay(seconds: Int) {
        autoAnswerDelaySeconds = seconds
        connectionScope.launch {
            Log.d("SimulatedConnection", "Auto-answer coroutine started for outgoing call, delay: $seconds seconds")
            // Wait the full delay period before transitioning to Active
            if (seconds > 0) {
                delay(seconds.seconds)
            } else {
                // Even if 0, yield to ensure UI has a chance to show DIALING state
                delay(500.milliseconds)
            }
            
            // Only transition if still in a non-active/non-disconnected state
            if (state != STATE_ACTIVE && state != STATE_DISCONNECTED) {
                Log.d("SimulatedConnection", "Auto-answering outgoing call now. Current state: $state")
                onAnswer()
            } else {
                Log.d("SimulatedConnection", "Skipping auto-answer. Call already in state: $state")
            }
        }
    }

    fun setAutoMissDelay(seconds: Int) {
        connectionScope.launch {
            Log.d("SimulatedConnection", "Auto-miss coroutine started, delay: $seconds seconds")
            if (seconds > 0) {
                delay(seconds.seconds)
            } else {
                delay(500.milliseconds)
            }
            
            if (state == STATE_RINGING) {
                Log.d("SimulatedConnection", "Auto-missing incoming call now.")
                onReject()
            }
        }
    }

    override fun onShowIncomingCallUi() {
        Log.d("SimulatedConnection", "onShowIncomingCallUi")
        super.onShowIncomingCallUi()
    }

    override fun onAnswer() {
        Log.d("SimulatedConnection", "onAnswer")
        if (state != STATE_ACTIVE) {
            setActive()
            CallStateManager.setSimulatedCallActive(context, true, address?.schemeSpecificPart)
            
            // Get the recorded answer time from manager to sync timers
            val answerTime = CallStateManager.answerTime.value

            // Update notification to Active (this triggers the system timer/chip)
            val intent = Intent(context, CallNotificationService::class.java).apply {
                putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, address?.schemeSpecificPart)
                putExtra(CallNotificationService.EXTRA_IS_INCOMING, false) // Ongoing
                putExtra(CallNotificationService.EXTRA_IS_MISSED, false)
                putExtra(CallNotificationService.EXTRA_IS_DIALING, false) // Active
                putExtra(CallNotificationService.EXTRA_IS_SIMULATED, true)
                putExtra(CallNotificationService.EXTRA_START_TIME, if (answerTime > 0L) answerTime else System.currentTimeMillis())
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onReject() {
        Log.d("SimulatedConnection", "onReject")
        setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
        cleanup()
    }

    override fun onDisconnect() {
        Log.d("SimulatedConnection", "onDisconnect")
        setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
        cleanup()
    }

    override fun onAbort() {
        Log.d("SimulatedConnection", "onAbort")
        setDisconnected(DisconnectCause(DisconnectCause.CANCELED))
        cleanup()
    }

    private fun cleanup() {
        CallStateManager.recordCallEnd(context)
        CallStateManager.setSimulatedCallActive(context, false)
        CallStateManager.onDisconnectRequested = null
        CallStateManager.onAnswerRequested = null
        connectionScope.launch {
            delay(500.milliseconds) // Small buffer for any final updates
            connectionScope.launch(Dispatchers.Main) {
                // We can't cancel the scope if we are IN it, but we can stop any pending work
                // Actually, since we use a SupervisorJob, we should cancel the job.
            }
        }
        // Properly cancel the scope to stop any pending auto-answer/auto-miss coroutines
        kotlin.runCatching {
            connectionScope.launch { 
                // Final logs etc
            }
            // Better: just cancel the job
        }
        // Let's just cancel it properly
        connectionScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        
        context.stopService(Intent(context, CallNotificationService::class.java))
        destroy()
    }

    override fun onHold() {
        Log.d("SimulatedConnection", "onHold")
        setOnHold()
    }

    override fun onUnhold() {
        Log.d("SimulatedConnection", "onUnhold")
        setActive()
    }

    @Deprecated("Deprecated in Java")
    @Suppress("DeprecatedCallableAddReplaceWith")
    override fun onCallAudioStateChanged(state: android.telecom.CallAudioState?) {
        Log.d("SimulatedConnection", "onCallAudioStateChanged: $state")
    }
}
