package com.example.alibi.util

import android.database.Cursor
import android.telecom.Call
import android.telecom.Connection
import com.example.alibi.telecom.TelecomConstants

/**
 * Unified extension to resolve a stable ID for any Telecom Call object.
 * Prioritizes Alibi's internal ID, then the Connection ID, then the hash code.
 */
fun Call.getAlibiId(): String {
    val extras = details.extras ?: android.os.Bundle.EMPTY
    return extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        ?: extras.getString(TelecomConstants.EXTRA_CONNECTION_ID)
        ?: hashCode().toString()
}

/**
 * Unified extension to resolve a stable ID for a Telecom Connection object.
 */
fun Connection.getAlibiId(): String {
    val extras = extras ?: android.os.Bundle.EMPTY
    return extras.getString(TelecomConstants.EXTRA_ALIBI_CALL_ID)
        ?: extras.getString(TelecomConstants.EXTRA_CONNECTION_ID)
        ?: hashCode().toString()
}

/**
 * Helper to safely extract a String from a Cursor.
 */
fun Cursor.getStringSafe(columnName: String): String? {
    val index = getColumnIndex(columnName)
    return if (index != -1) getString(index) else null
}

/**
 * Helper to safely extract an Int from a Cursor.
 */
fun Cursor.getIntSafe(columnName: String, fallback: Int = 0): Int {
    val index = getColumnIndex(columnName)
    return if (index != -1) getInt(index) else fallback
}

/**
 * Helper to safely extract a Long from a Cursor.
 */
fun Cursor.getLongSafe(columnName: String, fallback: Long = 0L): Long {
    val index = getColumnIndex(columnName)
    return if (index != -1) getLong(index) else fallback
}
