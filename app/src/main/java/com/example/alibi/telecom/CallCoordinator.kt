package com.example.alibi.telecom

import android.telecom.Call
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*

/**
 * Orchestrates cross-session policies like preemption and busy messaging.
 */
@OptIn(ExperimentalCoroutinesApi::class)
object CallCoordinator {
    private const val TAG = "CallCoordinator"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    val busyMessage: StateFlow<String?> = CallRepository.sessions
        .flatMapLatest { sessions ->
            if (sessions.isEmpty()) return@flatMapLatest flowOf(null)
            
            val metadataFlows = sessions.values.map { it.metadata }
            combine(metadataFlows) { metas ->
                val active = metas.filter { it.state != Call.STATE_DISCONNECTED && it.state != Call.STATE_DISCONNECTING }
                when {
                    active.any { it.isRealCall } -> "A real call is currently happening. Try again later."
                    active.any { it.isSimulated } -> "There is already an ongoing Simulated call. Try again later."
                    else -> null
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // Preemption Logic: Hang up simulated calls if a real one starts.
        CallRepository.sessions
            .flatMapLatest { sessions ->
                if (sessions.isEmpty()) return@flatMapLatest flowOf(Unit)
                
                val metadataFlows = sessions.values.map { it.metadata }
                combine(metadataFlows) { metas ->
                    val activeReal = metas.any { it.isRealCall && it.state != Call.STATE_DISCONNECTED }
                    if (activeReal) {
                        sessions.values.forEach { session ->
                            val meta = session.metadata.value
                            if (meta.isSimulated && meta.state != Call.STATE_DISCONNECTED) {
                                Log.i(TAG, "Preempting simulated session: ${session.id}")
                                session.hangup()
                            }
                        }
                    }
                }
            }
            .launchIn(scope)
    }
}
