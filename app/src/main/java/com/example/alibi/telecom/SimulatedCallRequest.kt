package com.example.alibi.telecom

import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.example.alibi.telecom.TelecomConstants.EXTRA_ALIBI_CALL_ID
import com.example.alibi.telecom.TelecomConstants.EXTRA_AUTO_ANSWER_DELAY
import com.example.alibi.telecom.TelecomConstants.EXTRA_CALL_FEATURES
import com.example.alibi.telecom.TelecomConstants.EXTRA_CALL_TYPE
import com.example.alibi.telecom.TelecomConstants.EXTRA_CUSTOM_START_TIME
import com.example.alibi.telecom.TelecomConstants.EXTRA_INTENDED_DURATION
import com.example.alibi.telecom.TelecomConstants.EXTRA_MIMIC_SIM_HANDLE
import com.example.alibi.telecom.TelecomConstants.EXTRA_PHONE_NUMBER

import java.util.UUID

/**
 * Encapsulates all metadata for a simulated call request.
 * Handles serialization to and from [Bundle] for Telecom propagation.
 */
data class SimulatedCallRequest(
    val phoneNumber: String,
    val direction: Int, // android.provider.CallLog.Calls.INCOMING_TYPE or OUTGOING_TYPE
    val startTime: Long? = null,
    val duration: Long? = null,
    val simHandle: PhoneAccountHandle? = null,
    val features: Int = 0,
    val autoAnswerDelay: Int = 0,
    val alibiId: String = "ALIBI_${UUID.randomUUID()}"
) {
    fun toBundle(): Bundle = Bundle().apply {
        putString(EXTRA_PHONE_NUMBER, phoneNumber)
        putInt(EXTRA_CALL_TYPE, direction)
        putString(EXTRA_ALIBI_CALL_ID, alibiId)
        
        startTime?.let { putLong(EXTRA_CUSTOM_START_TIME, it) }
        duration?.let { putLong(EXTRA_INTENDED_DURATION, it) }
        simHandle?.let { putParcelable(EXTRA_MIMIC_SIM_HANDLE, it) }
        
        putInt(EXTRA_CALL_FEATURES, features)
        putInt(EXTRA_AUTO_ANSWER_DELAY, autoAnswerDelay)
    }

    companion object {
        fun fromBundle(bundle: Bundle): SimulatedCallRequest {
            val outgoingExtras = bundle.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: Bundle.EMPTY
            
            // Helper to check both bundles for a value
            fun getString(key: String): String? {
                return bundle.getString(key) ?: outgoingExtras.getString(key)
            }

            fun getInt(key: String, default: Int): Int {
                return when {
                    bundle.containsKey(key) -> bundle.getInt(key)
                    outgoingExtras.containsKey(key) -> outgoingExtras.getInt(key)
                    else -> default
                }
            }

            fun getLong(key: String): Long? {
                return when {
                    bundle.containsKey(key) -> bundle.getLong(key)
                    outgoingExtras.containsKey(key) -> outgoingExtras.getLong(key)
                    else -> null
                }
            }

            val simHandle = if (Build.VERSION.SDK_INT >= 33) {
                bundle.getParcelable(EXTRA_MIMIC_SIM_HANDLE, PhoneAccountHandle::class.java)
                    ?: outgoingExtras.getParcelable(EXTRA_MIMIC_SIM_HANDLE, PhoneAccountHandle::class.java)
            } else {
                @Suppress("DEPRECATION")
                bundle.getParcelable(EXTRA_MIMIC_SIM_HANDLE)
                    ?: @Suppress("DEPRECATION") outgoingExtras.getParcelable(EXTRA_MIMIC_SIM_HANDLE)
            }

            return SimulatedCallRequest(
                phoneNumber = getString(EXTRA_PHONE_NUMBER) ?: "Unknown",
                direction = getInt(EXTRA_CALL_TYPE, android.provider.CallLog.Calls.INCOMING_TYPE),
                startTime = getLong(EXTRA_CUSTOM_START_TIME),
                duration = getLong(EXTRA_INTENDED_DURATION),
                simHandle = simHandle,
                features = getInt(EXTRA_CALL_FEATURES, 0),
                autoAnswerDelay = getInt(EXTRA_AUTO_ANSWER_DELAY, 0),
                alibiId = getString(EXTRA_ALIBI_CALL_ID) ?: "ALIBI_${System.currentTimeMillis()}"
            )
        }
    }
}
