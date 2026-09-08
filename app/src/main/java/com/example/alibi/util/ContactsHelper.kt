package com.example.alibi.util

import android.content.Context
import android.database.ContentObserver
import android.provider.ContactsContract
import android.util.Log
import com.example.alibi.ui.screens.ContactItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch

/**
 * Utility to provide a reactive stream of system contacts.
 * Uses ContentObserver to detect background syncs and external updates.
 */
class ContactsHelper(private val context: Context) {

    fun getContactsFlow(): Flow<List<ContactItem>> = callbackFlow {
        val observer = object : ContentObserver(null) {
            override fun onChange(selfChange: Boolean) {
                launch {
                    trySend(fetchContacts())
                }
            }
        }

        context.contentResolver.registerContentObserver(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            true,
            observer
        )

        awaitClose {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
    .onStart { emit(fetchContacts()) }
    .flowOn(Dispatchers.IO)
    .distinctUntilChanged()
    .conflate()

    private fun fetchContacts(): List<ContactItem> {
        val list = mutableListOf<ContactItem>()
        try {
            val cursor = context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null,
                null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val name = it.getStringSafe(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME) ?: "Unknown"
                    val number = it.getStringSafe(ContactsContract.CommonDataKinds.Phone.NUMBER)
                    if (!number.isNullOrBlank()) {
                        list.add(ContactItem(name, number))
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e("ContactsHelper", "SecurityException during contacts query - permission likely revoked", e)
        } catch (e: Exception) {
            Log.e("ContactsHelper", "Error querying contacts", e)
        }
        return list
    }
}
