package com.example.alibi.telecom

import android.content.ComponentName
import android.content.Context
import android.telecom.PhoneAccountHandle
import androidx.core.content.edit

/**
 * Dedicated helper for persisting Telecom-related user preferences.
 * Separated from CallStateManager to improve architectural isolation.
 */
class TelecomPrefs(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPersistedSimHandle(): PhoneAccountHandle? {
        val id = prefs.getString(KEY_SIM_ID, null) ?: return null
        val comp = prefs.getString(KEY_SIM_COMP, null) ?: return null
        return try {
            val component = ComponentName.unflattenFromString(comp) ?: return null
            PhoneAccountHandle(component, id)
        } catch (e: Exception) {
            null
        }
    }

    fun setPersistedSimHandle(handle: PhoneAccountHandle?) {
        prefs.edit {
            if (handle != null) {
                putString(KEY_SIM_ID, handle.id)
                putString(KEY_SIM_COMP, handle.componentName.flattenToString())
            } else {
                remove(KEY_SIM_ID)
                remove(KEY_SIM_COMP)
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "alibi_telecom_prefs"
        private const val KEY_SIM_ID = "mimic_sim_id"
        private const val KEY_SIM_COMP = "mimic_sim_component"
    }
}
