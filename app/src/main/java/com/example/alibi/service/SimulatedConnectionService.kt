package com.example.alibi.service

import android.os.Bundle
import android.telecom.*
import android.util.Log
import com.example.alibi.telecom.SimulatedCallRequest

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
        Log.d(TAG, "Metadata extracted: $callRequest")
        return SimulatedConnection(this, callRequest)
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
        Log.d(TAG, "Metadata extracted: $callRequest")
        return SimulatedConnection(this, callRequest)
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
    }

    companion object {
        private const val TAG = "SimulatedConnService"
    }
}
