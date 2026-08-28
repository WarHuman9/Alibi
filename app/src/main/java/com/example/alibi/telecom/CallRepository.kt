package com.example.alibi.telecom

import android.util.Log
import com.example.alibi.telecom.session.CallSession
import com.example.alibi.telecom.session.OptimisticCallSession
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Thread-safe registry for all active [CallSession] objects.
 */
object CallRepository {
    private const val TAG = "CallRepository"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private val _sessions = MutableStateFlow<Map<String, CallSession>>(emptyMap())
    val sessions: StateFlow<Map<String, CallSession>> = _sessions.asStateFlow()

    fun addSession(session: CallSession) {
        Log.d(TAG, "addSession: ${session.id}")
        
        _sessions.update { current ->
            // Concrete sessions (Real or Simulated) should replace any matching Optimistic sessions
            val isConcrete = session !is OptimisticCallSession
            val meta = session.metadata.value
            
            val optimisticId = if (isConcrete) {
                current.entries.find { (_, s) ->
                    s is OptimisticCallSession && s.metadata.value.number == meta.number
                }?.key
            } else null

            if (optimisticId != null) {
                Log.d(TAG, "Merging optimistic session $optimisticId into new session ${session.id}")
                current - optimisticId + (session.id to session)
            } else {
                current + (session.id to session)
            }
        }

        // Safety Timeout for Optimistic sessions to prevent UI deadlocks
        if (session is OptimisticCallSession) {
            scope.launch {
                delay(10000L)
                _sessions.update { current ->
                    if (current[session.id] === session) {
                        Log.w(TAG, "Optimistic session ${session.id} timed out. Removing.")
                        current - session.id
                    } else current
                }
            }
        }
    }

    fun removeSession(id: String) {
        Log.d(TAG, "removeSession: $id")
        _sessions.update { it - id }
    }

    fun getSession(id: String): CallSession? {
        return _sessions.value[id]
    }

    fun clear() {
        Log.d(TAG, "clear all sessions")
        _sessions.value = emptyMap()
    }
}
