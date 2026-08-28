package com.example.alibi.telecom.session

import android.os.Build
import android.telecom.Call
import android.telecom.VideoProfile
import com.example.alibi.telecom.CallMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Implementation of [CallSession] for system (real) calls.
 */
class RealCallSession(
    private val call: Call,
    override val id: String
) : CallSession {

    private val _metadata = MutableStateFlow(extractMetadata(call))
    override val metadata: StateFlow<CallMetadata> = _metadata.asStateFlow()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            updateMetadata()
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            updateMetadata()
        }
    }

    init {
        call.registerCallback(callback)
    }

    private fun updateMetadata() {
        _metadata.update { extractMetadata(call) }
    }

    private fun extractMetadata(call: Call): CallMetadata {
        val details = call.details
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        return CallMetadata(
            id = id,
            call = call,
            isSimulated = false,
            isRealCall = true,
            number = details.handle?.schemeSpecificPart ?: "Unknown",
            name = details.callerDisplayName ?: details.handle?.schemeSpecificPart ?: "Unknown",
            state = state,
            startTime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) details.creationTimeMillis else 0L,
            answerTime = if (state == Call.STATE_ACTIVE) details.connectTimeMillis else 0L,
            isHolding = (state == Call.STATE_HOLDING),
            simHandle = details.accountHandle,
            features = 0 // System calls don't use the custom features field yet
        )
    }

    override fun answer() {
        call.answer(VideoProfile.STATE_AUDIO_ONLY)
    }

    override fun hangup() {
        call.disconnect()
    }

    override fun hold() {
        call.hold()
    }

    override fun resume() {
        call.unhold()
    }

    fun cleanup() {
        call.unregisterCallback(callback)
    }
}
