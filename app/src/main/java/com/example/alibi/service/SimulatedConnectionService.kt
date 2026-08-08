package com.example.alibi.service

import android.content.Intent
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
        Log.d(TAG, "onCreateIncomingConnection")
        val connection = SimulatedConnection(this)
        
        setupConnection(connection, request)
        connection.setRinging()
        
        val metadata = extractMetadata(request)
        applyMetadata(connection, metadata, isIncoming = true)
        
        startNotification(metadata, isIncoming = true, isDialing = false)

        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed")
        CallStateManager.forceClearState(this)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d(TAG, "onCreateOutgoingConnection")
        val connection = SimulatedConnection(this)
        
        setupConnection(connection, request)
        connection.setDialing()
        
        val metadata = extractMetadata(request)
        applyMetadata(connection, metadata, isIncoming = false)
        
        connection.setAutoAnswerDelay(metadata.autoAnswerDelay)
        startNotification(metadata, isIncoming = false, isDialing = true)

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed")
        CallStateManager.forceClearState(this)
    }

    // --- Private Helpers ---

    private fun setupConnection(connection: Connection, request: ConnectionRequest?) {
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setInitializing()
    }

    private fun extractMetadata(request: ConnectionRequest?): CallMetadata {
        val extras = request?.extras ?: Bundle.EMPTY
        val outgoingExtras = extras.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS) ?: Bundle.EMPTY
        
        // Merge top-level and nested extras (support various OEM behaviors)
        return CallMetadata(
            phoneNumber = request?.address?.schemeSpecificPart ?: "Unknown",
            callType = extras.getInt(EXTRA_CALL_TYPE, CallLog.Calls.INCOMING_TYPE),
            startTime = extras.getLong(EXTRA_CUSTOM_START_TIME, -1L).takeIf { it != -1L }
                ?: outgoingExtras.getLong(EXTRA_CUSTOM_START_TIME, -1L).takeIf { it != -1L },
            duration = extras.getLong(EXTRA_INTENDED_DURATION, -1L).takeIf { it != -1L }
                ?: outgoingExtras.getLong(EXTRA_INTENDED_DURATION, -1L).takeIf { it != -1L },
            simHandle = getPhoneAccountHandle(extras, outgoingExtras),
            features = extras.getInt(EXTRA_CALL_FEATURES, 0).takeIf { it != 0 }
                ?: outgoingExtras.getInt(EXTRA_CALL_FEATURES, 0),
            autoAnswerDelay = extras.getInt(EXTRA_AUTO_ANSWER_DELAY, 0).takeIf { it != 0 }
                ?: outgoingExtras.getInt(EXTRA_AUTO_ANSWER_DELAY, 0)
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
        CallStateManager.setSimulatedCallActive(this, true, data.phoneNumber, state, data.callType)
        
        if (data.callType == CallLog.Calls.MISSED_TYPE) {
            val ringingTime = data.duration?.toInt() ?: 20
            connection.setAutoMissDelay(ringingTime)
        }
    }

    private fun startNotification(data: CallMetadata, isIncoming: Boolean, isDialing: Boolean) {
        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, data.phoneNumber)
            putExtra(CallNotificationService.EXTRA_IS_INCOMING, isIncoming)
            putExtra(CallNotificationService.EXTRA_IS_MISSED, data.callType == CallLog.Calls.MISSED_TYPE)
            putExtra(CallNotificationService.EXTRA_IS_DIALING, isDialing)
            putExtra(CallNotificationService.EXTRA_IS_SIMULATED, true)
        }
        ContextCompat.startForegroundService(this, intent)
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
