package com.example.alibi.telecom

import android.content.Context
import android.telecom.Call
import android.util.Log
import com.example.alibi.service.AudioHeartbeatManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.*

/**
 * Manages the lifecycle of [AudioHeartbeatManager] based on active sessions.
 * Task 22: Audio Policy decoupling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPolicyManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val heartbeatManager = AudioHeartbeatManager.getInstance(context)

    init {
        CallRepository.sessions
            .flatMapLatest { sessions ->
                if (sessions.isEmpty()) return@flatMapLatest flowOf(emptyList<CallMetadata>())
                combine(sessions.values.map { it.metadata }) { it.toList() }
            }
            .onEach { metas ->
                val hasRealCall = metas.any { it.isRealCall && it.state != Call.STATE_DISCONNECTED }
                val hasSimulatedCall = metas.any { it.isSimulated && it.state != Call.STATE_DISCONNECTED }

                if (hasRealCall) {
                    // Safety first: stop heartbeat if any real call is active
                    heartbeatManager.stop()
                } else if (hasSimulatedCall) {
                    // Only start heartbeat if there are simulated calls AND no real calls
                    heartbeatManager.start()
                } else {
                    // No active calls at all
                    heartbeatManager.stop()
                }
            }
            .launchIn(scope)
    }

    /**
     * Cleans up the observers and stops the heartbeat.
     */
    fun cleanup() {
        Log.d("AudioPolicyManager", "Cleaning up AudioPolicyManager")
        scope.cancel()
        heartbeatManager.stop()
    }
}
