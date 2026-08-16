package com.example.alibi.service

import android.os.Build
import android.os.Bundle
import android.provider.CallLog
import android.telecom.*
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulatedCallRequest
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
        
        val callRequest = SimulatedCallRequest.fromBundle(request?.extras ?: Bundle.EMPTY)
        val connection = SimulatedConnection(this, callRequest.alibiId)
        
        CallStateManager.registerConnection(connection.connectionId, connection)
        
        setupConnection(connection, request, callRequest.alibiId)
        connection.setRinging()
        
        Log.d(TAG, "Metadata extracted: $callRequest")
        applyMetadata(connection, callRequest, isIncoming = true)

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
        
        val callRequest = SimulatedCallRequest.fromBundle(request?.extras ?: Bundle.EMPTY)
        val connection = SimulatedConnection(this, callRequest.alibiId)
        
        CallStateManager.registerConnection(connection.connectionId, connection)
        
        setupConnection(connection, request, callRequest.alibiId)
        connection.setDialing()
        
        Log.d(TAG, "Metadata extracted: $callRequest")
        applyMetadata(connection, callRequest, isIncoming = false)
        
        connection.setAutoAnswerDelay(callRequest.autoAnswerDelay)

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
    }


    // --- Private Helpers ---

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

    private fun applyMetadata(connection: SimulatedConnection, data: SimulatedCallRequest, isIncoming: Boolean) {
        connection.setMetadata(
            number = data.phoneNumber,
            startTime = data.startTime ?: System.currentTimeMillis(),
            type = data.direction,
            duration = data.duration,
            sim = data.simHandle,
            features = data.features
        )

        CallStateManager.setCustomStartTime(data.startTime)
        CallStateManager.setIntendedDuration(data.duration)
        CallStateManager.setMimicSimHandle(data.simHandle)
        CallStateManager.setCallFeatures(data.features)
        
        val state = if (isIncoming) Call.STATE_RINGING else Call.STATE_DIALING
        CallStateManager.setSimulatedCallActive(true, data.phoneNumber, state, data.direction, id = connection.connectionId)
        
        if (data.direction == CallLog.Calls.MISSED_TYPE) {
            val ringingTime = data.duration?.toInt() ?: 20
            connection.setAutoMissDelay(ringingTime)
        }
    }

    companion object {
        private const val TAG = "SimulatedConnService"
    }
}
