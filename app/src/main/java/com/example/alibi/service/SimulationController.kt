package com.example.alibi.service

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.Connection
import android.util.Log
import com.example.alibi.telecom.CallAction
import com.example.alibi.telecom.CallLogSnapshot
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulatedCallRequest
import com.example.alibi.telecom.SimulationPhase
import com.example.alibi.util.CallLogHelper
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The "Brain" for all simulated calls.
 * Task 18: Decoupled simulation logic from CallStateManager and SimulatedConnection.
 */
object SimulationController {
    private const val TAG = "SimulationController"
    private val controllerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeConnections = ConcurrentHashMap<String, SimulatedConnection>()
    private val activeJobs = ConcurrentHashMap<String, ConcurrentHashMap<String, Job>>()
    private val isSimulationAnswered = ConcurrentHashMap<String, AtomicBoolean>()

    init {
        controllerScope.launch {
            CallStateManager.actions.collect { (id, action) ->
                when (action) {
                    CallAction.ANSWER -> answerSimulatedCall(id)
                    CallAction.HANGUP -> disconnectSimulatedCall(id)
                    CallAction.HOLD -> holdSimulatedCall(id, true)
                    CallAction.RESUME -> holdSimulatedCall(id, false)
                }
            }
        }
    }

    /**
     * Registers a connection and initializes its simulation logic.
     */
    fun registerConnection(id: String, connection: SimulatedConnection) {
        Log.d(TAG, "registerConnection: $id")
        activeConnections[id] = connection
        activeJobs[id] = ConcurrentHashMap()
        isSimulationAnswered[id] = AtomicBoolean(false)

        val request = connection.request
        
        // Setup initial timers
        if (request.direction == android.provider.CallLog.Calls.OUTGOING_TYPE) {
            setAutoAnswerDelay(id, request.autoAnswerDelay)
        } else if (request.direction == android.provider.CallLog.Calls.MISSED_TYPE) {
            val ringingTime = request.duration?.toInt() ?: 20
            setAutoMissDelay(id, ringingTime)
        }

        startCloakingWatchdog(id)
    }

    /**
     * Unregisters a connection and cleans up its jobs.
     */
    fun unregisterConnection(id: String) {
        Log.d(TAG, "unregisterConnection: $id")
        cancelAllJobs(id)
        activeJobs.remove(id)
        activeConnections.remove(id)
        isSimulationAnswered.remove(id)
    }

    fun answerSimulatedCall(id: String) {
        val connection = activeConnections[id] ?: return
        val answered = isSimulationAnswered[id] ?: return

        Log.d(TAG, "answerSimulatedCall: $id")
        val wasCloaked = connection.state == Connection.STATE_ACTIVE

        if (answered.getAndSet(true)) {
            Log.d(TAG, "Call $id already answered. Ignoring.")
            return
        }

        // Cancel watchdog and transition timers
        cancelJob(id, "cloaking")
        cancelJob(id, "autoAnswer")
        cancelJob(id, "autoMiss")

        if (!wasCloaked) {
            connection.setActive()
        } else {
            Log.i(TAG, "Call $id was cloaked. Restoring audio.")
            CallStateManager.onMuteRequested?.invoke(false)
        }

        CallStateManager.updateCallState(id, Call.STATE_ACTIVE, SimulationPhase.SIMULATED_ACTIVE)
        AudioHeartbeatManager.getInstance(connection.context).start()

        // Start duration timer
        connection.request.duration?.takeIf { it > 0 }?.let { duration ->
            startTimer(id, "duration", duration * 1000L) {
                Log.d(TAG, "Intended duration reached for $id. Hanging up.")
                disconnectSimulatedCall(id, userInitiated = false)
            }
        }
    }

    fun disconnectSimulatedCall(id: String, userInitiated: Boolean = true) {
        val connection = activeConnections[id] ?: return
        Log.d(TAG, "disconnectSimulatedCall: $id (userInitiated=$userInitiated)")
        
        // Use connection.terminate() to ensure consistent cleanup path
        connection.terminate(userInitiated)
    }

    fun holdSimulatedCall(id: String, hold: Boolean) {
        val connection = activeConnections[id] ?: return
        Log.d(TAG, "holdSimulatedCall: $id, hold=$hold")
        if (hold) {
            connection.setOnHold()
            CallStateManager.updateCallState(id, Call.STATE_HOLDING)
            AudioHeartbeatManager.getInstance(connection.context).stop()
            CallStateManager.setIsHolding(true, id)
        } else {
            // Restore to active
            connection.setActive()
            CallStateManager.updateCallState(id, Call.STATE_ACTIVE)
            AudioHeartbeatManager.getInstance(connection.context).start()
            CallStateManager.setIsHolding(false, id)
        }
    }

    private fun startCloakingWatchdog(id: String) {
        if (Build.VERSION.SDK_INT >= 35) { // Android 15/16+
            startTimer(id, "cloaking", 40000L) {
                val connection = activeConnections[id]
                if (connection != null && (connection.state == Connection.STATE_DIALING || connection.state == Connection.STATE_RINGING)) {
                    Log.i(TAG, "Watchdog: Cloaking $id state to ACTIVE to avoid system kill.")
                    connection.setActive()
                    CallStateManager.onMuteRequested?.invoke(true)
                }
            }
        }
    }

    private fun setAutoAnswerDelay(id: String, seconds: Int) {
        if (seconds < 0) return
        val delay = if (seconds > 0) seconds * 1000L else 500L
        startTimer(id, "autoAnswer", delay) {
            val answered = isSimulationAnswered[id]
            if (answered != null && !answered.get()) {
                Log.d(TAG, "Auto-answering $id after delay: $seconds s")
                answerSimulatedCall(id)
            }
        }
    }

    private fun setAutoMissDelay(id: String, seconds: Int) {
        if (seconds <= 0) return
        startTimer(id, "autoMiss", seconds * 1000L) {
            val answered = isSimulationAnswered[id]
            if (answered != null && !answered.get()) {
                Log.d(TAG, "Auto-missing $id after delay: $seconds s")
                disconnectSimulatedCall(id, userInitiated = false)
            }
        }
    }

    private fun startTimer(id: String, type: String, delayMillis: Long, onFinish: suspend () -> Unit) {
        val jobs = activeJobs[id] ?: return
        jobs[type]?.cancel()
        jobs[type] = controllerScope.launch {
            delay(delayMillis)
            onFinish()
            jobs.remove(type)
        }
    }

    private fun cancelJob(id: String, type: String) {
        activeJobs[id]?.remove(type)?.cancel()
    }

    private fun cancelAllJobs(id: String) {
        activeJobs[id]?.values?.forEach { it.cancel() }
        activeJobs[id]?.clear()
    }

    /**
     * Explicitly triggers logging for a call before it's removed from state.
     * Task 21: Atomic Logging Synchronization.
     */
    fun triggerLogging(id: String, userInitiated: Boolean) {
        Log.d(TAG, "triggerLogging: $id (userInitiated=$userInitiated)")
        
        val connection = activeConnections[id]
        val intendedDuration = connection?.request?.duration
        val context = connection?.context
        
        val snapshot = CallStateManager.completeCall(id)
        
        if (snapshot != null && context != null) {
            controllerScope.launch {
                recordCallEnd(context, snapshot, intendedDuration, userInitiated)
            }
        } else {
            Log.w(TAG, "triggerLogging: Failed to get snapshot or context for $id")
        }
    }

    /**
     * Records the end of a simulated call using the provided snapshot.
     */
    suspend fun recordCallEnd(
        context: Context,
        snapshot: CallLogSnapshot,
        intendedDuration: Long?,
        isUserTerminated: Boolean
    ) = withContext(Dispatchers.IO + NonCancellable) {
        Log.d(TAG, "recordCallEnd: Processing log for ${snapshot.number}")

        var finalType = snapshot.type
        var finalDuration = 0L

        if (snapshot.answerTime > 0L) {
            // Task 21: Answered calls must be INCOMING or OUTGOING
            if (finalType != android.provider.CallLog.Calls.OUTGOING_TYPE) {
                finalType = android.provider.CallLog.Calls.INCOMING_TYPE
            }
            
            val actualElapsed = (snapshot.endTime - snapshot.answerTime) / 1000
            finalDuration = if (isUserTerminated) {
                actualElapsed
            } else if (intendedDuration != null) {
                kotlin.math.min(actualElapsed, intendedDuration)
            } else {
                actualElapsed
            }
        } else {
            // Not answered
            when (snapshot.type) {
                android.provider.CallLog.Calls.MISSED_TYPE -> {
                    finalDuration = 0L
                }
                android.provider.CallLog.Calls.INCOMING_TYPE -> {
                    finalType = if (isUserTerminated) android.provider.CallLog.Calls.REJECTED_TYPE else android.provider.CallLog.Calls.MISSED_TYPE
                    finalDuration = 0L
                }
                android.provider.CallLog.Calls.OUTGOING_TYPE -> {
                    finalDuration = 0L
                }
                else -> {
                    finalDuration = 0L
                }
            }
        }

        val helper = CallLogHelper.getInstance(context)
        helper.insertCallLog(snapshot.number, finalDuration, snapshot.startTime, finalType, snapshot.simHandle, snapshot.features)
        Log.d(TAG, "recordCallEnd: Insertion requested for ${snapshot.number} (duration=$finalDuration)")
    }
}
