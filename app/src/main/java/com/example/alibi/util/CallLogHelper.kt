package com.example.alibi.util

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class CallLogHelper private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO)

    data class CallLogItem(
        val id: Long,
        val number: String,
        val type: Int,
        val date: Long,
        val duration: Long,
        val name: String? = null,
        val features: Int = 0,
        val phoneAccountId: String? = null,
    )

    fun getRecentCalls(limit: Int = 20): List<CallLogItem> {
        val list = mutableListOf<CallLogItem>()
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return list
        }

        val cursor = context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls._ID,
                CallLog.Calls.NUMBER,
                CallLog.Calls.TYPE,
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.FEATURES,
                CallLog.Calls.PHONE_ACCOUNT_ID
            ),
            null,
            null,
            "${CallLog.Calls.DATE} DESC LIMIT $limit"
        )

        cursor?.use {
            val idIdx = it.getColumnIndex(CallLog.Calls._ID)
            val numIdx = it.getColumnIndex(CallLog.Calls.NUMBER)
            val typeIdx = it.getColumnIndex(CallLog.Calls.TYPE)
            val dateIdx = it.getColumnIndex(CallLog.Calls.DATE)
            val durIdx = it.getColumnIndex(CallLog.Calls.DURATION)
            val nameIdx = it.getColumnIndex(CallLog.Calls.CACHED_NAME)
            val featIdx = it.getColumnIndex(CallLog.Calls.FEATURES)
            val accIdx = it.getColumnIndex(CallLog.Calls.PHONE_ACCOUNT_ID)

            while (it.moveToNext()) {
                list.add(
                    CallLogItem(
                        id = it.getLong(idIdx),
                        number = it.getString(numIdx),
                        type = it.getInt(typeIdx),
                        date = it.getLong(dateIdx),
                        duration = it.getLong(durIdx),
                        name = if (nameIdx != -1) it.getString(nameIdx) else null,
                        features = if (featIdx != -1) it.getInt(featIdx) else 0,
                        phoneAccountId = if (accIdx != -1) it.getString(accIdx) else null
                    )
                )
            }
        }
        return list
    }

    companion object {
        @Volatile
        @SuppressLint("StaticFieldLeak")
        private var INSTANCE: CallLogHelper? = null

        fun getInstance(context: Context): CallLogHelper {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: CallLogHelper(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    /**
     * Inserts a call record into the system call log asynchronously.
     *
     * @param phoneNumber The phone number of the call.
     * @param duration The duration of the call in seconds.
     * @param timestamp The start time of the call in milliseconds.
     * @param callType The type of call (e.g., CallLog.Calls.INCOMING_TYPE, CallLog.Calls.OUTGOING_TYPE, CallLog.Calls.MISSED_TYPE).
     */
    fun insertCallLog(
        phoneNumber: String,
        duration: Long,
        timestamp: Long,
        callType: Int,
        simHandle: android.telecom.PhoneAccountHandle? = null,
        features: Int = 0
    ) {
        scope.launch {
            try {
                val values = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, phoneNumber)
                    put(CallLog.Calls.DATE, timestamp)
                    put(CallLog.Calls.DURATION, duration)
                    put(CallLog.Calls.TYPE, callType)
                    put(CallLog.Calls.NEW, 1)
                    put(CallLog.Calls.FEATURES, features)
                    
                    if (simHandle != null) {
                        put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, simHandle.componentName.flattenToString())
                        put(CallLog.Calls.PHONE_ACCOUNT_ID, simHandle.id)
                    }
                }

                val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, values)
                Log.d("CallLogHelper", "Call log inserted: $uri")
            } catch (e: Exception) {
                Log.e("CallLogHelper", "Error inserting call log", e)
            }
        }
    }
}
