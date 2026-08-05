package com.example.alibi.service

import android.telecom.Call
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.provider.CallLog
import android.os.Build
import android.util.Log

import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.alibi.telecom.CallStateManager

class SimulatedConnectionService : ConnectionService() {

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d("SimulatedConnService", "onCreateIncomingConnection")
        val connection = SimulatedConnection(this)
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setInitializing()
        connection.setRinging()

        val phoneNumber = request?.address?.schemeSpecificPart
        val callType = request?.extras?.getInt("EXTRA_CALL_TYPE", CallLog.Calls.INCOMING_TYPE) 
            ?: CallLog.Calls.INCOMING_TYPE
        
        val customStartTime = request?.extras?.getLong("EXTRA_CUSTOM_START_TIME", -1L)?.takeIf { it != -1L }
        val intendedDuration = request?.extras?.getLong("EXTRA_INTENDED_DURATION", -1L)?.takeIf { it != -1L }
        val mimicSimHandle = if (Build.VERSION.SDK_INT >= 33) {
            request?.extras?.getParcelable("EXTRA_MIMIC_SIM_HANDLE", PhoneAccountHandle::class.java)
        } else {
            @Suppress("DEPRECATION")
            request?.extras?.getParcelable<PhoneAccountHandle>("EXTRA_MIMIC_SIM_HANDLE")
        }
        val callFeatures = request?.extras?.getInt("EXTRA_CALL_FEATURES", 0) ?: 0
        
        CallStateManager.setCustomStartTime(customStartTime)
        CallStateManager.setIntendedDuration(intendedDuration)
        CallStateManager.setMimicSimHandle(mimicSimHandle)
        CallStateManager.setCallFeatures(callFeatures)
        
        CallStateManager.setSimulatedCallActive(this, true, phoneNumber, Call.STATE_RINGING, type = callType)

        if (callType == CallLog.Calls.MISSED_TYPE) {
            val ringingTime = request?.extras?.getLong("EXTRA_INTENDED_DURATION", 20L)?.toInt() ?: 20
            connection.setAutoMissDelay(ringingTime)
        }

        // Start notification service
        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(CallNotificationService.EXTRA_IS_INCOMING, true)
            putExtra(CallNotificationService.EXTRA_IS_MISSED, callType == CallLog.Calls.MISSED_TYPE)
            putExtra(CallNotificationService.EXTRA_IS_DIALING, false)
            putExtra(CallNotificationService.EXTRA_IS_SIMULATED, true)
        }
        ContextCompat.startForegroundService(this, intent)

        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e("SimulatedConnService", "onCreateIncomingConnectionFailed")
        CallStateManager.setSimulatedCallActive(this, active = false)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.d("SimulatedConnService", "onCreateOutgoingConnection")
        val connection = SimulatedConnection(this)
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.setInitializing()
        connection.setDialing()
        
        val phoneNumber = request?.address?.schemeSpecificPart
        val callType = request?.extras?.getInt("EXTRA_CALL_TYPE", CallLog.Calls.OUTGOING_TYPE)
            ?: CallLog.Calls.OUTGOING_TYPE
            
        // 1. Update Manager state to DIALING first
        val outgoingExtras = request?.extras?.getBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS)
        val customStartTime = outgoingExtras?.getLong("EXTRA_CUSTOM_START_TIME", -1L)?.takeIf { it != -1L }
            ?: request?.extras?.getLong("EXTRA_CUSTOM_START_TIME", -1L).takeIf { it != -1L }
        
        val intendedDuration = outgoingExtras?.getLong("EXTRA_INTENDED_DURATION", -1L)?.takeIf { it != -1L }
            ?: request?.extras?.getLong("EXTRA_INTENDED_DURATION", -1L).takeIf { it != -1L }

        val mimicSimHandle = if (Build.VERSION.SDK_INT >= 33) {
            outgoingExtras?.getParcelable("EXTRA_MIMIC_SIM_HANDLE", PhoneAccountHandle::class.java)
                ?: request?.extras?.getParcelable("EXTRA_MIMIC_SIM_HANDLE", PhoneAccountHandle::class.java)
        } else {
            @Suppress("DEPRECATION")
            outgoingExtras?.getParcelable<PhoneAccountHandle>("EXTRA_MIMIC_SIM_HANDLE")
                ?: @Suppress("DEPRECATION") request?.extras?.getParcelable<PhoneAccountHandle>("EXTRA_MIMIC_SIM_HANDLE")
        }
        
        val callFeatures = outgoingExtras?.getInt("EXTRA_CALL_FEATURES", 0)
            ?: request?.extras?.getInt("EXTRA_CALL_FEATURES", 0)
            ?: 0

        CallStateManager.setCustomStartTime(customStartTime)
        CallStateManager.setIntendedDuration(intendedDuration)
        CallStateManager.setMimicSimHandle(mimicSimHandle)
        CallStateManager.setCallFeatures(callFeatures)
        CallStateManager.setSimulatedCallActive(this, true, phoneNumber, Call.STATE_DIALING, type = callType)

        // 2. Start the auto-answer delay (might trigger onAnswer immediately if delay is 0)
        val autoAnswerDelay = outgoingExtras?.getInt("EXTRA_AUTO_ANSWER_DELAY", 0) 
            ?: request?.extras?.getInt("EXTRA_AUTO_ANSWER_DELAY", 0) 
            ?: 0
        
        Log.d("SimulatedConnService", "Starting outgoing call with delay: $autoAnswerDelay")
        connection.setAutoAnswerDelay(autoAnswerDelay)

        // 3. Start notification service (In DIALING state initially)
        val intent = Intent(this, CallNotificationService::class.java).apply {
            putExtra(CallNotificationService.EXTRA_PHONE_NUMBER, phoneNumber)
            putExtra(CallNotificationService.EXTRA_IS_INCOMING, false)
            putExtra(CallNotificationService.EXTRA_IS_MISSED, false)
            putExtra(CallNotificationService.EXTRA_IS_DIALING, true)
            putExtra(CallNotificationService.EXTRA_IS_SIMULATED, true)
        }
        ContextCompat.startForegroundService(this, intent)

        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e("SimulatedConnService", "onCreateOutgoingConnectionFailed")
        CallStateManager.setSimulatedCallActive(this, active = false)
    }
}
