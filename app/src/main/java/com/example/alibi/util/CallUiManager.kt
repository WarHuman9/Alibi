package com.example.alibi.util

import android.content.Context
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles UI state tracking for call sessions.
 * Lockscreen activity launches are delegated exclusively to SystemUI via FullScreenIntent.
 */
class CallUiManager(@Suppress("UNUSED_PARAMETER") private val context: Context) {
    private val launchedUiForCalls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    fun clearUiState(callId: String) {
        launchedUiForCalls.remove(callId)
    }
}
