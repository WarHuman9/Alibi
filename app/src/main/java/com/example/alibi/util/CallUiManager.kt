package com.example.alibi.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.alibi.MainActivity
import com.example.alibi.telecom.TelecomConstants
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles UI-related logic for calls, such as launching the main activity.
 * Task 10: Decoupled from CallService.
 */
class CallUiManager(private val context: Context) {
    private val TAG = "CallUiManager"
    private val launchedUiForCalls = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * Launches the MainActivity for real calls with selective UI.
     */
    fun showRealCallUi(callId: String) {
        if (launchedUiForCalls.contains(callId)) {
            Log.d(TAG, "UI already launched for call $callId. Skipping.")
            return
        }
        
        Log.d(TAG, "Launching MainActivity selective UI for real call $callId")
        launchedUiForCalls.add(callId)
        
        val uiIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(TelecomConstants.EXTRA_REAL_CALL, true)
        }
        
        val pendingIntent = PendingIntent.getActivity(
            context, 0, uiIntent, 
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        try {
            Log.d(TAG, "Sending PendingIntent for real call UI.")
            pendingIntent.send()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send PendingIntent, falling back to startActivity", e)
            context.startActivity(uiIntent)
        }
    }

    fun clearUiState(callId: String) {
        launchedUiForCalls.remove(callId)
    }
}
