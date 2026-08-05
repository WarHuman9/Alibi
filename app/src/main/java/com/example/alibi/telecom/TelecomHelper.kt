package com.example.alibi.telecom

import android.content.ComponentName
import android.content.Context
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
import androidx.core.content.ContextCompat
import com.example.alibi.service.SimulatedConnectionService
import androidx.core.content.edit

class TelecomHelper(private val context: Context) {

    private val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
    private val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val componentName = ComponentName(context, SimulatedConnectionService::class.java)
    private val phoneAccountHandle = PhoneAccountHandle(componentName, "SimulatedCallAccount")
    private val prefs = context.getSharedPreferences("alibi_telecom_prefs", Context.MODE_PRIVATE)

    data class SimAccount(
        val handle: PhoneAccountHandle,
        val label: String,
        val address: String?
    )

    fun getPreferredSimId(): String? = prefs.getString("preferred_sim_id", null)

    fun setPreferredSimId(id: String?) {
        prefs.edit {putString("preferred_sim_id", id) }
    }

    data class NetworkSnapshot(
        val isHdCapable: Boolean,
        val isWifiCallingActive: Boolean
    )

    fun getNetworkSnapshot(): NetworkSnapshot {
        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        
        var isHd = false
        if (hasPhoneState) {
            val networkType = telephonyManager.dataNetworkType
            isHd = when (networkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> true
                TelephonyManager.NETWORK_TYPE_IWLAN -> true
                else -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && networkType == TelephonyManager.NETWORK_TYPE_NR
            }
        }

        var isWifiCalling = false
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if (capabilities != null) {
            // IWLAN typically indicates Voice over Wi-Fi in telephony terms
            if (hasPhoneState && telephonyManager.dataNetworkType == TelephonyManager.NETWORK_TYPE_IWLAN) {
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
                // Filter out our own simulated account if it's in the list
                if (handle.componentName.packageName != context.packageName) {
                    SimAccount(
                        handle = handle,
                        label = account.label.toString(),
                        address = account.address?.schemeSpecificPart
                    )
                } else null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun registerPhoneAccount() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // We use CAPABILITY_SELF_MANAGED to support our ConnectionService architecture.
            // This is deprecated in API 34 in favor of the new CallControl API, but 
            // is still required for backward compatibility.
            @Suppress("DEPRECATION")
            val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "Alibi Simulated Call")
                .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                .build()
            telecomManager.registerPhoneAccount(phoneAccount)
        }
    }

    fun startIncomingCall(
        phoneNumber: String,
        callType: Int = android.provider.CallLog.Calls.INCOMING_TYPE,
        customStartTime: Long? = null,
        durationSeconds: Long? = null,
        mimicSimHandle: PhoneAccountHandle? = null,
        features: Int = 0
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val extras = Bundle().apply {
                putParcelable(
                    TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                    Uri.fromParts("tel", phoneNumber, null)
                )
                putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
                putInt("EXTRA_CALL_TYPE", callType)
                if (customStartTime != null) {
                    putLong("EXTRA_CUSTOM_START_TIME", customStartTime)
                }
                if (durationSeconds != null) {
                    putLong("EXTRA_INTENDED_DURATION", durationSeconds)
                }
                if (mimicSimHandle != null) {
                    putParcelable("EXTRA_MIMIC_SIM_HANDLE", mimicSimHandle)
                }
                putInt("EXTRA_CALL_FEATURES", features)
            }
            try {
                telecomManager.addNewIncomingCall(phoneAccountHandle, extras)
            } catch (e: Exception) {
                e.printStackTrace()
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
        val extras = Bundle().apply {
            putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            putInt("EXTRA_CALL_TYPE", android.provider.CallLog.Calls.OUTGOING_TYPE)
            
            // Put custom extras in EXTRA_OUTGOING_CALL_EXTRAS to ensure they reach ConnectionService
            val outgoingExtras = Bundle().apply {
                putInt("EXTRA_AUTO_ANSWER_DELAY", autoAnswerDelay)
                if (customStartTime != null) {
                    putLong("EXTRA_CUSTOM_START_TIME", customStartTime)
                }
                if (durationSeconds != null) {
                    putLong("EXTRA_INTENDED_DURATION", durationSeconds)
                }
                if (mimicSimHandle != null) {
                    putParcelable("EXTRA_MIMIC_SIM_HANDLE", mimicSimHandle)
                }
                putInt("EXTRA_CALL_FEATURES", features)
            }
            putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtras)
            
            // Also put it in the top level just in case
            if (customStartTime != null) {
                putLong("EXTRA_CUSTOM_START_TIME", customStartTime)
            }
            if (durationSeconds != null) {
                putLong("EXTRA_INTENDED_DURATION", durationSeconds)
            }
            if (mimicSimHandle != null) {
                putParcelable("EXTRA_MIMIC_SIM_HANDLE", mimicSimHandle)
            }
            putInt("EXTRA_CALL_FEATURES", features)
        }
        val uri = Uri.fromParts("tel", phoneNumber, null)
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                telecomManager.placeCall(uri, extras)
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
            e.printStackTrace()
        }
    }
}
