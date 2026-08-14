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
import android.annotation.SuppressLint
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.alibi.service.SimulatedConnectionService
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /**
     * Checks if the app has the necessary phone-related permissions.
     * On Android 11+, READ_PHONE_NUMBERS is required for certain TelecomManager operations.
     */
    fun hasPhonePermissions(): Boolean {
        val hasState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val hasNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
        } else true
        return hasState && hasNumbers
    }

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

    suspend fun getNetworkSnapshot(): NetworkSnapshot = withContext(Dispatchers.IO) {
        val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        
        val isHd = hasPhoneState && when (val networkType = telephonyManager.dataNetworkType) {
            TelephonyManager.NETWORK_TYPE_LTE, TelephonyManager.NETWORK_TYPE_IWLAN -> true
            else -> (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) && (networkType == TelephonyManager.NETWORK_TYPE_NR)
        }

        var isWifiCalling = false
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork)
        if ((capabilities != null) && hasPhoneState) {
            if (telephonyManager.dataNetworkType == TelephonyManager.NETWORK_TYPE_IWLAN) {
                isWifiCalling = true
            }
        }

        NetworkSnapshot(isHdCapable = isHd, isWifiCallingActive = isWifiCalling)
    }

    @SuppressLint("MissingPermission")
    suspend fun getCallCapableSims(): List<SimAccount> = withContext(Dispatchers.IO) {
        if (!hasPhonePermissions()) {
            Log.w(TAG, "getCallCapableSims: Missing phone permissions")
            return@withContext emptyList()
        }
        
        try {
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
        } catch (_: Exception) {
            Log.e("TelecomHelper", "Error fetching SIM accounts")
            emptyList()
        }
    }

    @Suppress("DEPRECATION")
    suspend fun registerPhoneAccount() = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!hasPhonePermissions()) {
                Log.w(TAG, "registerPhoneAccount: Aborted due to missing phone permissions")
                return@withContext
            }

            try {
                // Master Level: Idempotent Registration
                // Check if account already exists with correct configuration
                val currentAccount = telecomManager.getPhoneAccount(phoneAccountHandle)
                
                val needsUpdate = currentAccount == null || 
                    currentAccount.label != SIMULATED_ACCOUNT_LABEL ||
                    !currentAccount.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)

                if (needsUpdate) {
                    val phoneAccount = PhoneAccount.builder(phoneAccountHandle, SIMULATED_ACCOUNT_LABEL)
                        .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
                        .addSupportedUriScheme(PhoneAccount.SCHEME_TEL)
                        .build()
                    
                    telecomManager.registerPhoneAccount(phoneAccount)
                    Log.d(TAG, "Idempotent registration: PhoneAccount created/updated")
                } else {
                    Log.d(TAG, "Idempotent registration: Account already valid, skipping")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register PhoneAccount", e)
            }
        }
    }

    /**
     * Verifies if the simulated account is currently recognized and enabled by the system.
     */
    suspend fun isAccountRegistered(): Boolean = withContext(Dispatchers.IO) {
        if (!hasPhonePermissions()) return@withContext false
        try {
            val account = telecomManager.getPhoneAccount(phoneAccountHandle)
            account != null && account.isEnabled
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Purges legacy account IDs from the system registry.
     * This is intended to be called when the app is granted the Default Dialer role.
     */
    suspend fun cleanupLegacyAccounts() = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cleanup skipped: Missing READ_PHONE_STATE")
            return@withContext
        }

        try {
            val handles = telecomManager.callCapablePhoneAccounts
            var cleanupCount = 0
            
            handles.forEach { handle ->
                // Safety Guard: Only touch accounts belonging to OUR package
                if (handle.componentName.packageName == context.packageName) {
                    // Purge if ID is in legacy list OR if it's not our stable ID
                    val isLegacy = TelecomConstants.LEGACY_ACCOUNT_IDS.contains(handle.id)
                    val isNotStable = handle.id != SIMULATED_ACCOUNT_ID
                    
                    if (isLegacy || isNotStable) {
                        telecomManager.unregisterPhoneAccount(handle)
                        cleanupCount++
                        Log.d(TAG, "Purged legacy account: ${handle.id}")
                    }
                }
            }
            if (cleanupCount > 0) Log.i(TAG, "Cleanup complete. Removed $cleanupCount ghost accounts.")
        } catch (e: Exception) {
            Log.e(TAG, "Error during legacy account cleanup", e)
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
        
        customStartTime?.let { putLong(EXTRA_CUSTOM_START_TIME, it) }
        durationSeconds?.let { putLong(EXTRA_INTENDED_DURATION, it) }
        mimicSimHandle?.let { putParcelable(EXTRA_MIMIC_SIM_HANDLE, it) }
        
        putInt(EXTRA_CALL_FEATURES, features)
        putInt(EXTRA_AUTO_ANSWER_DELAY, autoAnswerDelay)
    }

    suspend fun startIncomingCall(
        phoneNumber: String,
        callType: Int = CallLog.Calls.INCOMING_TYPE,
        customStartTime: Long? = null,
        durationSeconds: Long? = null,
        mimicSimHandle: PhoneAccountHandle? = null,
        features: Int = 0
    ) = withContext(Dispatchers.IO) {
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

    suspend fun startOutgoingCall(
        phoneNumber: String,
        autoAnswerDelay: Int = 0,
        customStartTime: Long? = null,
        durationSeconds: Long? = null,
        mimicSimHandle: PhoneAccountHandle? = null,
        features: Int = 0
    ) = withContext(Dispatchers.IO) {
        CallStateManager.forceClearState(context)

        val extras = createCallBundle(
            CallLog.Calls.OUTGOING_TYPE, 
            customStartTime, 
            durationSeconds, 
            mimicSimHandle, 
            features, 
            autoAnswerDelay
        )
        
        // Duplicate for OEMs (Samsung/Pixel) that look in nested bundle
        val outgoingExtras = Bundle(extras)
        extras.putBundle(TelecomManager.EXTRA_OUTGOING_CALL_EXTRAS, outgoingExtras)

        val uri = Uri.fromParts("tel", phoneNumber, null)
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Placing simulated outgoing call via: $SIMULATED_ACCOUNT_ID")
                telecomManager.placeCall(uri, extras)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to place outgoing call", e)
        }
    }

    suspend fun placeRealCall(phoneNumber: String, simHandle: PhoneAccountHandle? = null) = withContext(Dispatchers.IO) {
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
