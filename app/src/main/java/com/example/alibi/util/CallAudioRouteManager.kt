package com.example.alibi.util

import android.os.Handler
import android.os.Looper
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log

/**
 * Handles audio route management (earpiece/speaker) for calls.
 * Task 10: Decoupled from CallService.
 */
class CallAudioRouteManager(private val service: InCallService) {
    private val TAG = "CallAudioRouteManager"
    private val mainHandler = Handler(Looper.getMainLooper())

    fun setSpeaker(enabled: Boolean) {
        try {
            Log.d(TAG, "Setting speaker: $enabled")
            @Suppress("DEPRECATION")
            service.setAudioRoute(if (enabled) CallAudioState.ROUTE_SPEAKER else CallAudioState.ROUTE_EARPIECE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set speaker route", e)
        }
    }

    fun setMuted(muted: Boolean) {
        try {
            Log.d(TAG, "Setting mute: $muted")
            service.setMuted(muted)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set muted state", e)
        }
    }

    /**
     * Schedules a delayed switch to earpiece for real calls.
     * This is often needed because the system might take a moment to stabilize the audio path.
     */
    fun scheduleEarpieceTransition(delayMillis: Long = 200) {
        Log.d(TAG, "Scheduling audio route to EARPIECE in ${delayMillis}ms.")
        mainHandler.postDelayed({
            try {
                @Suppress("DEPRECATION")
                service.setAudioRoute(CallAudioState.ROUTE_EARPIECE)
                Log.d(TAG, "Delayed audio route set to EARPIECE.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set audio route in delayed handler", e)
            }
        }, delayMillis)
    }
}
