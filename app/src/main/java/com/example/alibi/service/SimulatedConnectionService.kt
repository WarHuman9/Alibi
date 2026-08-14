package com.example.alibi.service

import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants.EXTRA_AUTO_ANSWER_DELAY
import com.example.alibi.telecom.TelecomConstants.EXTRA_CALL_FEATURES
import com.example.alibi.telecom.TelecomConstants.EXTRA_CALL_TYPE
import com.example.alibi.telecom.TelecomConstants.EXTRA_CUSTOM_START_TIME
import com.example.alibi.telecom.TelecomConstants.EXTRA_INTENDED_DURATION
import com.example.alibi.telecom.TelecomConstants.EXTRA_MIMIC_SIM_HANDLE

/**
 * Service to handle simulated [Connection] creation.
 * Unifies metadata extraction for both incoming and outgoing paths.
 */
class SimulatedConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateIncomingConnection: ${request?.address}")
        
        val alibiId = extractAlibiId(request)
        val connection = SimulatedConnection(this, alibiId)
        
        CallStateManager.registerConnection(connection.connectionId, connection)
        
        setupConnection(connection, request, alibiId)
        connection.setRinging()
        
        val metadata = extractMetadata(request)
        Log.d(TAG, "Metadata extracted: $metadata")
        applyMetadata(connection, metadata, isIncoming = true)

        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed")
    }


    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection: ${request?.address}")
        
        val alibiId = extractAlibiId(request)
        val connection = SimulatedConnection(this, alibiId)
        
        CallStateManager.registerConnection(connection.connectionId, connection)
        
        setupConnection(connection, request, alibiId)
        connection.setDialing()
        
        val metadata = extractMetadata(request)
        Log.d(TAG, "Metadata extracted: $metadata")
        applyMetadata(connection, metadata, isIncoming = false)
        
        connection.setAutoAnswerDelay(metadata.autoAnswerDelay)

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
    }


    // --- Private Helpers ---

    private fun extractAlibiId(request: ConnectionRequest?): String? {
        val requestExtras = request?.extras ?: Bundle.EMPTY
        val nestedExtras = requestExtras.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: Bundle.EMPTY
        
        return requestExtras.getString(com.example.alibi.telecom.TelecomConstants.EXTRA_ALIBI_CALL_ID)
            ?: nestedExtras.getString(com.example.alibi.telecom.TelecomConstants.EXTRA_ALIBI_CALL_ID)
    }

    private fun setupConnection(connection: SimulatedConnection, request: ConnectionRequest?, alibiId: String?) {
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        
        val extras = connection.extras ?: Bundle()
        extras.putString(com.example.alibi.telecom.TelecomConstants.EXTRA_CONNECTION_ID, connection.connectionId)
        
        if (alibiId != null) {
            Log.d(TAG, "Propagating Alibi Call ID to Connection extras: $alibiId")
            extras.putString(com.example.alibi.telecom.TelecomConstants.EXTRA_ALIBI_CALL_ID, alibiId)
        }

        connection.setExtras(extras)
        connection.setInitializing()
    }

    private fun extractMetadata(request: ConnectionRequest?): CallMetadata {
        val extras = request?.extras ?: Bundle.EMPTY
        val outgoingExtras = extras.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: Bundle.EMPTY
        
        // Helper to check both bundles for a value
        fun getInt(key: String, default: Int): Int {
            val v = extras.getInt(key, -1).takeIf { it != -1 }
            if (v != null) return v
            return outgoingExtras.getInt(key, default)
        }

        fun getLong(key: String): Long? {
            return extras.getLong(key, -1L).takeIf { it != -1L }
                ?: outgoingExtras.getLong(key, -1L).takeIf { it != -1L }
        }

        return CallMetadata(
            phoneNumber = request?.address?.schemeSpecificPart ?: "Unknown",
            callType = getInt(EXTRA_CALL_TYPE, CallLog.Calls.INCOMING_TYPE),
            startTime = getLong(EXTRA_CUSTOM_START_TIME),
            duration = getLong(EXTRA_INTENDED_DURATION),
            simHandle = getPhoneAccountHandle(extras, outgoingExtras),
            features = getInt(EXTRA_CALL_FEATURES, 0),
            autoAnswerDelay = getInt(EXTRA_AUTO_ANSWER_DELAY, 0)
        )
    }

    private fun getPhoneAccountHandle(extras: Bundle, nested: Bundle): PhoneAccountHandle? {
        return if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(EXTRA_MIMIC_SIM_HANDLE, PhoneAccountHandle::class.java)
                ?: nested.getParcelable(EXTRA_MIMIC_SIM_HANDLE, PhoneAccountHandle::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(EXTRA_MIMIC_SIM_HANDLE)
                ?: @Suppress("DEPRECATION") nested.getParcelable(EXTRA_MIMIC_SIM_HANDLE)
        }
    }

    private fun applyMetadata(connection: SimulatedConnection, data: CallMetadata, isIncoming: Boolean) {
        connection.setMetadata(
            number = data.phoneNumber,
            startTime = data.startTime ?: System.currentTimeMillis(),
            type = data.callType,
            duration = data.duration,
            sim = data.simHandle,
            features = data.features
        )

        CallStateManager.setCustomStartTime(data.startTime)
        CallStateManager.setIntendedDuration(data.duration)
        CallStateManager.setMimicSimHandle(data.simHandle)
        CallStateManager.setCallFeatures(data.features)
        
        val state = if (isIncoming) Call.STATE_RINGING else Call.STATE_DIALING
        CallStateManager.setSimulatedCallActive(true, data.phoneNumber, state, data.callType, id = connection.connectionId)
        
        if (data.callType == CallLog.Calls.MISSED_TYPE) {
            val ringingTime = data.duration?.toInt() ?: 20
            connection.setAutoMissDelay(ringingTime)
        }
    }

    private data class CallMetadata(
        val phoneNumber: String,
        val callType: Int,
        val startTime: Long?,
        val duration: Long?,
        val simHandle: PhoneAccountHandle?,
        val features: Int,
        val autoAnswerDelay: Int
    )

    companion object {
        private const val TAG = "SimulatedConnService"
    }
}
