package com.example.alibi.service

import android.os.Bundle
import android.telecom.*
import android.util.Log
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulatedCallRequest
import com.example.alibi.telecom.TelecomConstants

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
        val extras = request?.extras ?: Bundle.EMPTY
        val callRequest = SimulatedCallRequest.fromBundle(extras)
        Log.d(TAG, "Metadata extracted for incoming call: $callRequest")
        
        return SimulatedConnection(this, callRequest)
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        val extras = request?.extras ?: Bundle.EMPTY
        val callId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        Log.e(TAG, "onCreateIncomingConnectionFailed for callId: $callId")
        
        if (callId != null) {
            CallStateManager.removeCall(callId)
        }
    }


    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection: ${request?.address}")
        val extras = request?.extras ?: Bundle.EMPTY
        val callRequest = SimulatedCallRequest.fromBundle(extras)
        Log.d(TAG, "Metadata extracted for outgoing call: $callRequest")
        
        return SimulatedConnection(this, callRequest)
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        val extras = request?.extras ?: Bundle.EMPTY
        val callId = extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        Log.e(TAG, "onCreateOutgoingConnectionFailed for callId: $callId")
        
        if (callId != null) {
            CallStateManager.removeCall(callId)
        }
    }

    companion object {
        private const val TAG = "SimulatedConnService"
    }
}
