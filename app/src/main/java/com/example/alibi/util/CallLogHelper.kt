package com.example.alibi.util

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable

/**
 * Utility to interact with the system CallLog database.
 * Provides reactive flows and safe insertion methods.
 */
class CallLogHelper private constructor(private val context: Context) {

    private val helperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    data class CallLogItem(
        val id: Long,
        val number: String,
        val type: Int,
        val date: Long,
        val duration: Long,
        val name: String? = null,
        val formattedNumber: String? = null, // Fallback for Jio/Android 16
        val numberType: Int = 0,
        val features: Int = 0,
        val phoneAccountId: String? = null,
        val phoneAccountComponent: String? = null,
    )

    /**
     * Returns a Flow that emits a list of recent calls whenever the call log database changes.
     * Limit increased to 500 for better performance/reliability trade-off.
     */
    fun getRecentCallsFlow(limit: Int = 500): Flow<List<CallLogItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                // Task 16: Move blocking ContentResolver query to Dispatchers.IO
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    val calls = getRecentCalls(limit)
                    trySend(calls)
                }
            }
        }

        try {
            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
                context.contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, observer)
            } else {
                Log.w(TAG, "READ_CALL_LOG permission not granted. Flow will be empty.")
                trySend(emptyList())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error registering ContentObserver", e)
            trySend(emptyList())
        }

        // Initial push
        trySend(getRecentCalls(limit))

        awaitClose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering ContentObserver", e)
            }
        }
    }.onStart { emit(getRecentCalls(limit)) }

    /**
     * Fetches the recent calls from the system database.
     * Uses manual limiting to avoid "Invalid token LIMIT" crashes on certain Android versions.
     */
    fun getRecentCalls(limit: Int = 500): List<CallLogItem> {
        val list = mutableListOf<CallLogItem>()
        
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return list
        }

        try {
            val cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                COLUMNS,
                null,
                null,
                DEFAULT_SORT
            )

            cursor?.use {
                val idIdx = it.getColumnIndex(CallLog.Calls._ID)
                val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
                val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
                val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
                val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
                val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
                val formatIdx = it.getColumnIndex(CallLog.Calls.CACHED_FORMATTED_NUMBER)
                val numTypeIdx = it.getColumnIndex(CallLog.Calls.CACHED_NUMBER_TYPE)
                val featIdx = it.getColumnIndex(CallLog.Calls.FEATURES)
                val accIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)
                val compIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)

                var count = 0
                while (it.moveToNext() && (count < limit)) {
                    val rawNumber = it.getString(numIdx)
                    val cachedFormat = if (formatIdx != -1) it.getString(formatIdx) else null
                    
                    list.add(
                        CallLogItem(
                            id = it.getLong(idIdx),
                            number = rawNumber ?: cachedFormat ?: "Unknown",
                            type = it.getInt(typeIdx),
                            date = it.getLong(dateIdx),
                            duration = it.getLong(durIdx),
                            name = if (nameIdx != -1) it.getString(nameIdx) else null,
                            formattedNumber = cachedFormat,
                            numberType = if (numTypeIdx != -1) it.getInt(numTypeIdx) else 0,
                            features = if (featIdx != -1) it.getInt(featIdx) else 0,
                            phoneAccountId = if (accIdx != -1) it.getString(accIdx) else null,
                            phoneAccountComponent = if (compIdx != -1) it.getString(compIdx) else null
                        )
                    )
                    count++
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error querying call log database", e)
        }
        return list
    }

    /**
     * Inserts a call record into the system call log.
     * Refined for Task 20: Minimalist mapping and no smart fallback.
     */
    suspend fun insertCallLog(
        phoneNumber: String,
        duration: Long,
        timestamp: Long,
        callType: Int,
        simHandle: android.telecom.PhoneAccountHandle? = null,
        features: Int = 0
    ) = withContext(Dispatchers.IO + NonCancellable) {
        Log.d(TAG, "insertCallLog: Request received for $phoneNumber (duration=$duration, type=$callType)")
        try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NUMBER, phoneNumber)
                put(CallLog.Calls.DATE, timestamp)
                put(CallLog.Calls.DURATION, duration)
                put(CallLog.Calls.TYPE, callType)
                put(CallLog.Calls.NEW, 1)
                put(CallLog.Calls.IS_READ, 0)
                put(CallLog.Calls.NUMBER_PRESENTATION, CallLog.Calls.PRESENTATION_ALLOWED)
                
                // Optional Features
                if (features != 0) {
                    put(CallLog.Calls.FEATURES, features)
                }

                // Explicit SIM Handle - NO SMART FALLBACK
                if (simHandle != null) {
                    put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, simHandle.componentName.flattenToString())
                    put(CallLog.Calls.PHONE_ACCOUNT_ID, simHandle.id)
                }
            }

            val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
            
            if (uri != null) {
                Log.d(TAG, "insertCallLog: Success! Call log inserted: $uri")
                context.contentResolver.notifyChange(CallLog.Calls.CONTENT_URI, null)
            } else {
                Log.e(TAG, "insertCallLog: Fail. ContentResolver returned null URI")
            }
        } catch (e: Exception) {
            Log.e(TAG, "insertCallLog: Exception", e)
        }
    }

    companion object {
        private const val TAG = "CallLogHelper"
        private const val DEFAULT_SORT = "${CallLog.Calls.DATE} DESC, ${CallLog.Calls._ID} DESC"
        
        private val COLUMNS = arrayOf(
            CallLog.Calls._ID,
            CallLog.Calls.NUMBER,
            CallLog.Calls.TYPE,
            CallLog.Calls.DATE,
            CallLog.Calls.DURATION,
            CallLog.Calls.CACHED_NAME,
            CallLog.Calls.CACHED_FORMATTED_NUMBER,
            CallLog.Calls.CACHED_NUMBER_TYPE,
            CallLog.Calls.FEATURES,
            CallLog.Calls.PHONE_ACCOUNT_ID,
            CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME
        )

        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: CallLogHelper? = null

        fun getInstance(context: Context): CallLogHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallLogHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
