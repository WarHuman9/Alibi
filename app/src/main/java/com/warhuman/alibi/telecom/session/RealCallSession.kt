package com.warhuman.alibi.telecom.session

import android.content.Context
import android.os.Build
import android.telecom.Call
import android.telecom.VideoProfile
import com.warhuman.alibi.telecom.CallMetadata
import com.warhuman.alibi.telecom.CallStateManager
import com.warhuman.alibi.util.ContactResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Implementation of [CallSession] for system (real) calls.
 */
class RealCallSession(
    private val call: Call,
    override val id: String,
    private val context: Context? = null
) : CallSession {

    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _metadata = MutableStateFlow(extractMetadata(call))
    override val metadata: StateFlow<CallMetadata> = _metadata.asStateFlow()

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            updateMetadata()
            CallStateManager.onCallStateChangedHook?.invoke(call, state)
            if (state == Call.STATE_DISCONNECTED || state == Call.STATE_DISCONNECTING) {
                CallStateManager.removeCall(id)
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            updateMetadata()
        }
    }

    init {
        call.registerCallback(callback)
        resolveContactNameAsync()
    }

    private fun updateMetadata() {
        _metadata.update { extractMetadata(call) }
        resolveContactNameAsync()
    }

    private fun resolveContactNameAsync() {
        val ctx = context ?: return
        sessionScope.launch {
            val number = _metadata.value.number
            val callerIdName = call.details.callerDisplayName
            val resolvedName = ContactResolver.resolveName(ctx, number, callerIdName)
            if (resolvedName != _metadata.value.name) {
                _metadata.update { it.copy(name = resolvedName) }
            }
        }
    }

    private fun extractMetadata(call: Call): CallMetadata {
        val details = call.details
        val state = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            details.state
        } else {
            @Suppress("DEPRECATION")
            call.state
        }

        val rawNumber = details.handle?.schemeSpecificPart?.ifBlank { null }
        val number = rawNumber ?: "Unknown"
        val fastName = ContactResolver.resolveFast(number, details.callerDisplayName)

        return CallMetadata(
            id = id,
            call = call,
            isSimulated = false,
            isRealCall = true,
            number = number,
            name = fastName,
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
        sessionScope.cancel()
    }
}
