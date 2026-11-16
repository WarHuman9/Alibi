package com.warhuman.alibi.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telephony.PhoneNumberUtils
import android.util.Log
import android.util.LruCache
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Utility to resolve contact names from phone numbers using ContactsContract.PhoneLookup.
 * Implements an in-memory LRU cache (capacity: 750 unique numbers) and a 4-tier resolution cascade.
 */
object ContactResolver {
    private const val TAG = "ContactResolver"
    private const val CACHE_SIZE = 750

    private val cache = LruCache<String, String>(CACHE_SIZE)

    /**
     * Normalizes phone numbers so different formatting of the same number
     * (+1 (555) 019-9100 vs +15550199100) collapse into a single unique key.
     */
    fun normalizeKey(phoneNumber: String): String {
        val clean = PhoneNumberUtils.normalizeNumber(phoneNumber)
        val digits = if (!clean.isNullOrBlank()) clean else phoneNumber.replace(Regex("[^0-9+]"), "")
        return if (digits.length > 10 && (digits.startsWith("+") || digits.startsWith("0"))) digits.takeLast(10) else digits
    }

    /**
     * Robust telephonic phone number matcher that checks exact string equality,
     * normalized key matching, and Android's native PhoneNumberUtils.compare.
     */
    fun isSameNumber(num1: String?, num2: String?): Boolean {
        if (num1.isNullOrBlank() || num2.isNullOrBlank()) return false
        if (num1 == num2) return true
        val k1 = normalizeKey(num1)
        val k2 = normalizeKey(num2)
        if (k1 == k2) return true
        if (k1.length >= 7 && k2.length >= 7 && (k1.endsWith(k2) || k2.endsWith(k1))) return true
        return PhoneNumberUtils.compare(num1, num2)
    }

    /**
     * Fast initial synchronous lookup using normalized memory cache key or carrier/device fallback.
     */
    fun resolveFast(phoneNumber: String?, callerDisplayName: String?): String {
        val number = phoneNumber?.ifBlank { null }
        val callerIdName = callerDisplayName?.ifBlank { null }

        if (number != null) {
            val key = normalizeKey(number)
            val cached = cache.get(key)
            if (!cached.isNullOrBlank()) {
                return cached
            }
        }

        return callerIdName ?: number ?: "Unknown"
    }

    /**
     * Complete asynchronous contact resolution querying ContactsContract on Dispatchers.IO.
     * Enforces the 4-tier resolution cascade:
     * 1. LRU Cache (Normalized unique key)
     * 2. ContactsContract.PhoneLookup query
     * 3. Network / Device callerDisplayName
     * 4. Phone number / "Unknown"
     */
    suspend fun resolveName(context: Context, phoneNumber: String?, callerDisplayName: String?): String = withContext(Dispatchers.IO) {
        val number = phoneNumber?.ifBlank { null } ?: return@withContext callerDisplayName?.ifBlank { null } ?: "Unknown"
        val callerIdName = callerDisplayName?.ifBlank { null }
        val key = normalizeKey(number)

        // Tier 1: Check memory cache using normalized key
        val cached = cache.get(key)
        if (!cached.isNullOrBlank()) {
            return@withContext cached
        }

        // Tier 2: Check ContactsContract if READ_CONTACTS permission is granted
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_CONTACTS
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            val resolved = queryPhoneLookup(context, number)
            if (!resolved.isNullOrBlank()) {
                cache.put(key, resolved)
                return@withContext resolved
            }
        }

        // Tier 3 & 4: Network/Device Caller ID -> Phone number -> "Unknown"
        val fallback = callerIdName ?: number
        cache.put(key, fallback)
        return@withContext fallback
    }

    private fun queryPhoneLookup(context: Context, phoneNumber: String): String? {
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex != -1) {
                        return cursor.getString(nameIndex)?.ifBlank { null }
                    }
                }
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "READ_CONTACTS permission missing during PhoneLookup query", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error querying PhoneLookup for number: $phoneNumber", e)
            null
        }
    }

    /**
     * Pre-populates the cache with phone numbers and names (e.g. from Contacts or Recent Calls).
     * Automatically normalizes numbers to deduplicate entries.
     */
    fun warmupCache(items: List<Pair<String, String>>) {
        var addedCount = 0
        items.forEach { (rawNumber, name) ->
            val num = rawNumber.ifBlank { null }
            val contactName = name.ifBlank { null }
            if (num != null && contactName != null) {
                val key = normalizeKey(num)
                if (cache.get(key) == null) {
                    cache.put(key, contactName)
                    addedCount++
                }
            }
        }
        Log.d(TAG, "warmupCache: Added $addedCount unique items. Current cache size: ${cache.size()}")
    }

    /**
     * Flushes the memory cache when system contacts change.
     */
    fun clearCache() {
        cache.evictAll()
        Log.d(TAG, "Contact cache cleared")
    }
}
