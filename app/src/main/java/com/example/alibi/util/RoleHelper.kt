package com.example.alibi.util

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager

object RoleHelper {
    fun isDialerRoleHeld(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false
            } else {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                val defaultDialer = telecomManager?.defaultDialerPackage
                defaultDialer != null && context.packageName == defaultDialer
            }
        } catch (_: Exception) {
            false
        }
    }
}
