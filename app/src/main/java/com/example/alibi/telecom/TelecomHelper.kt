package com.example.alibi.telecom

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.Manifest
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.service.SimulatedConnectionService
import androidx.core.content.edit
import com.example.alibi.telecom.TelecomConstants.EXTRA_AUTO_ANSWER_DELAY
import com.example.alibi.telecom.TelecomConstants.EXTRA_CALL_FEATURES
import com.example.alibi.telecom.TelecomConstants.EXTRA_CALL_TYPE
import com.example.alibi.telecom.TelecomConstants.EXTRA_CUSTOM_START_TIME
import com.example.alibi.telecom.TelecomConstants.EXTRA_INTENDED_DURATION
import com.example.alibi.telecom.TelecomConstants.EXTRA_MIMIC_SIM_HANDLE
import com.example.alibi.telecom.TelecomConstants.SIMULATED_ACCOUNT_ID
import com.example.alibi.telecom.TelecomConstants.SIMULATED_ACCOUNT_LABEL

/**
 * Helper class to manage system-level Telecom interactions.
 * Handles phone account registration, SIM discovery, and initiating simulated calls.
 */
class TelecomHelper(private val context: Context) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    private val componentName = ComponentName(context, SimulatedConnectionService::class.java)
    private val phoneAccountHandle = PhoneAccountHandle(componentName, SIMULATED_ACCOUNT_ID)
    private val prefs = context.getSharedPreferences("alibi_telecom_prefs", Context.MODE_PRIVATE)

    data class SimAccount(
        val handle: PhoneAccountHandle,
        val label: String,
        val address: String?
    )

    fun getPreferredSimId(): String? = prefs.getString("preferred_sim_id", null)

    fun setPreferredSimId(id: String?) {
        prefs.edit { putString("preferred_sim_id", id) }
    }

    data class NetworkSnapshot(
        val isHdCapable: Boolean,
        val isWifiCallingActive: Boolean
    )

    fun getNetworkSnapshot(): NetworkSnapshot {
        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        
        val isHd = hasPhoneState && when (val networkType = telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> true
            else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && networkType == TelephonyManager.NETWORK_TYPE_NR
        }

        var isWifiCalling = false
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null && hasPhoneState) {
            if (telephonyManager.dataNetworkType == TelephonyManager.NETWORK_TYPE_IWLAN) {
                isWifiCalling = true
            }
        }

        return NetworkSnapshot(isHdCapable = isHd, isWifiCallingActive = isWifiCalling)
    }

    fun getCallCapableSims(): List<SimAccount> {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }
        
        return try {
            telecomManager.callCapablePhoneAccounts.mapNotNull { handle ->
                val account = telecomManager.getPhoneAccount(handle)
                if (handle.componentName.packageName != context.packageName) {
                    SimAccount(
                        handle = handle,
                        label = account.label.toString(),
                        address = account.address?.schemeSpecificPart
                    )
                } else null
            }
        } catch (e: Exception) {
            Log.e("TelecomHelper", "Error fetching SIM accounts", e)
            emptyList()
        }
    }

    fun registerPhoneAccount() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, SIMULATED_ACCOUNT_LABEL)
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(phoneAccount)
        }
    }

    /**
     * Common logic to pack call metadata into a Bundle.
     */
    private fun createCallBundle(
        callType: Int,
        customStartTime: Long?,
        durationSeconds: Long?,
        mimicSimHandle: PhoneAccountHandle?,
        features: Int,
        autoAnswerDelay: Int = 0
    ): Bundle = Bundle().apply {
        putInt(EXTRA_CALL_TYPE, callType)
        putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
        
        if (customStartTime != null) putLong(EXTRA_CUSTOM_START_TIME, customStartTime)
        if (durationSeconds != null) putLong(EXTRA_INTENDED_DURATION, durationSeconds)
        if (mimicSimHandle != null) putParcelable(EXTRA_MIMIC_SIM_HANDLE, mimicSimHandle)
        
        putInt(EXTRA_CALL_FEATURES, features)
        putInt(EXTRA_AUTO_ANSWER_DELAY, autoAnswerDelay)
    }

    fun startIncomingCall(
        phoneNumber: String,
        callType: Int = CallLog.Calls.INCOMING_TYPE,
        customStartTime: Long? = null,
        durationSeconds: Long? = null,
        mimicSimHandle: PhoneAccountHandle? = null,
        features: Int = 0
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CallStateManager.forceClearState(context)

            val extras = createCallBundle(callType, customStartTime, durationSeconds, mimicSimHandle, features).apply {
                putParcelable(TelecomManager.EXTRA_INCOMING_CALL_ADDRESS, Uri.fromParts("tel", phoneNumber, null))
            }
            
            try {
                telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
            } catch (e: Exception) {
                Log.e("TelecomHelper", "Failed to add incoming call", e)
            }
        }
    }

    fun startOutgoingCall(
        phoneNumber: String,
        autoAnswerDelay: Int = 0,
        customStartTime: Long? = null,
        durationSeconds: Long? = null,
        mimicSimHandle: PhoneAccountHandle? = null,
        features: Int = 0
    ) {
        CallStateManager.forceClearState(context)

        val extras = createCallBundle(
            CallLog.Calls.OUTGOING_TYPE, 
            customStartTime, 
            durationSeconds, 
            mimicSimHandle, 
            features, 
            autoAnswerDelay
        )
        
        // Some OEMs require extras both at top-level AND in EXTRA_OUTGOING_CALL_EXTRAS
        val outgoingExtras = Bundle(extras)
        extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtras)

        val uri = Uri.fromParts("tel", phoneNumber, null)
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                telecomManager.placeCall(uri, extras)
            }
        } catch (e: Exception) {
            Log.e("TelecomHelper", "Failed to place outgoing call", e)
        }
    }

    fun placeRealCall(phoneNumber: String, simHandle: PhoneAccountHandle? = null) {
        val uri = Uri.fromParts("tel", phoneNumber, null)
        val extras = Bundle().apply {
            if (simHandle != null) {
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, simHandle)
            }
        }
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                telecomManager.placeCall(uri, extras)
            }
        } catch (e: Exception) {
            Log.e("TelecomHelper", "Failed to place real call", e)
        }
    }

    companion object {
        private const val TAG = "TelecomHelper"
    }
}
