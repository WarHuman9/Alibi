package com.warhuman.alibi.util

import android.content.ContentValues
import android.content.Context
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
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
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import java.util.Calendar

/**
 * Utility to interact with the system CallLog database.
 * Provides reactive flows, same-day event grouping, and safe insertion methods.
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

    data class GroupedCallLogItem(
        val id: Long,
        val number: String,
        val name: String? = null,
        val count: Int = 1,
        val date: Long,
        val duration: Long,
        val type: Int,
        val features: Int = 0,
        val phoneAccountId: String? = null,
        val phoneAccountComponent: String? = null,
        val calls: List<CallLogItem> = emptyList()
    )

    /**
     * Returns a Flow that emits a list of recent calls whenever the call log database changes.
     */
    fun getRecentCallsFlow(limit: Int = 500): Flow<List<CallLogItem>> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                helperScope.launch {
                    trySend(getRecentCalls(limit))
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

        awaitClose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering ContentObserver", e)
            }
        }
    }
    .onStart { 
        emit(getRecentCalls(limit)) 
    }
    .flowOn(Dispatchers.IO)
    .conflate()

    /**
     * Fetches recent calls from the system database.
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
                var count = 0
                while (it.moveToNext() && (count < limit)) {
                    val rawNumber = it.getStringSafe(CallLog.Calls.NUMBER)
                    val cachedFormat = it.getStringSafe(CallLog.Calls.CACHED_FORMATTED_NUMBER)
                    
                    val resolvedNumber = rawNumber 
                        ?: cachedFormat 
                        ?: it.getStringSafe(CallLog.Calls.CACHED_NAME) 
                        ?: "Unknown"

                    list.add(
                        CallLogItem(
                            id = it.getLongSafe(CallLog.Calls._ID),
                            number = resolvedNumber,
                            type = it.getIntSafe(CallLog.Calls.TYPE),
                            date = it.getLongSafe(CallLog.Calls.DATE),
                            duration = it.getLongSafe(CallLog.Calls.DURATION),
                            name = it.getStringSafe(CallLog.Calls.CACHED_NAME),
                            formattedNumber = cachedFormat,
                            numberType = it.getIntSafe(CallLog.Calls.CACHED_NUMBER_TYPE),
                            features = it.getIntSafe(CallLog.Calls.FEATURES),
                            phoneAccountId = it.getStringSafe(CallLog.Calls.PHONE_ACCOUNT_ID),
                            phoneAccountComponent = it.getStringSafe(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME)
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
     * Groups raw call log items by (normalizedPhoneNumber, calendarDay).
     * Multiple calls to/from the same number on the same day condense into 1 item
     * with count > 1 and the latest timestamp.
     */
    fun groupCallsByDay(rawCalls: List<CallLogItem>): List<GroupedCallLogItem> {
        val result = mutableListOf<GroupedCallLogItem>()
        val groupedMap = mutableMapOf<Pair<String, Long>, MutableList<CallLogItem>>()
        val keysOrder = mutableListOf<Pair<String, Long>>()

        for (item in rawCalls) {
            val normalizedNum = ContactResolver.normalizeKey(item.number)
            val dayKey = getDayKey(item.date)
            val compositeKey = Pair(normalizedNum, dayKey)

            if (!groupedMap.containsKey(compositeKey)) {
                groupedMap[compositeKey] = mutableListOf()
                keysOrder.add(compositeKey)
            }
            groupedMap[compositeKey]?.add(item)
        }

        for (key in keysOrder) {
            val group = groupedMap[key] ?: continue
            val latest = group.first()
            result.add(
                GroupedCallLogItem(
                    id = latest.id,
                    number = latest.number,
                    name = latest.name,
                    count = group.size,
                    date = latest.date,
                    duration = group.sumOf { it.duration },
                    type = latest.type,
                    features = group.fold(0) { acc, elem -> acc or elem.features },
                    phoneAccountId = latest.phoneAccountId,
                    phoneAccountComponent = latest.phoneAccountComponent,
                    calls = group
                )
            )
        }
        return result
    }

    private fun getDayKey(timestamp: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = timestamp
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    /**
     * Inserts a call record into the system call log using a Universal Version-Safe strategy.
     */
    suspend fun insertCallLog(
        phoneNumber: String,
        duration: Long,
        timestamp: Long,
        callType: Int,
        simHandle: android.telecom.PhoneAccountHandle? = null,
        features: Int = 0
    ) = withContext(Dispatchers.IO + NonCancellable) {
        val sanitizedNumber = phoneNumber.trim().takeIf { it.isNotEmpty() } ?: "Unknown"
        val sanitizedDate = if (timestamp > 0) timestamp else System.currentTimeMillis()

        Log.d(TAG, "insertCallLog: Starting Universal Version-Safe insertion for $sanitizedNumber")

        if (Build.VERSION.SDK_INT >= 34) {
            try {
                val enhancedValues = ContentValues().apply {
                    put(CallLog.Calls.NUMBER, sanitizedNumber)
                    put(CallLog.Calls.DATE, sanitizedDate)
                    put(CallLog.Calls.DURATION, duration)
                    put(CallLog.Calls.TYPE, callType)
                    put(CallLog.Calls.NEW, 1)
                    put(CallLog.Calls.IS_READ, 0)
                    put(CallLog.Calls.NUMBER_PRESENTATION, CallLog.Calls.PRESENTATION_ALLOWED)

                    if (features != 0) {
                        put(CallLog.Calls.FEATURES, features)
                    }
                    simHandle?.let { handle ->
                        handle.componentName?.let { comp ->
                            put(CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME, comp.flattenToString())
                        }
                        put(CallLog.Calls.PHONE_ACCOUNT_ID, handle.id)
                    }
                }
                
                val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, enhancedValues)
                if (uri != null) {
                    Log.d(TAG, "Enhanced insertion success: $uri")
                    context.contentResolver.notifyChange(CallLog.Calls.CONTENT_URI, null)
                    return@withContext
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Enhanced insertion failed: ${e.message}. Falling back to minimalist.", e)
            }
        }

        try {
            val minimalistValues = ContentValues().apply {
                put(CallLog.Calls.NUMBER, sanitizedNumber)
                put(CallLog.Calls.DATE, sanitizedDate)
                put(CallLog.Calls.DURATION, duration)
                put(CallLog.Calls.TYPE, callType)
            }
            
            val uri = context.contentResolver.insert(CallLog.Calls.CONTENT_URI, minimalistValues)
            if (uri != null) {
                Log.d(TAG, "Minimalist insertion success: $uri")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Critical failure in minimalist insertion fallback", e)
        } finally {
            context.contentResolver.notifyChange(CallLog.Calls.CONTENT_URI, null)
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
