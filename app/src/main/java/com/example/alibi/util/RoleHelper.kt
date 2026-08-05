package com.example.alibi.util

import android.app.role.RoleManager
import android.content.Context
import android.os.Build
import android.telecom.TelecomManager

object RoleHelper {
    fun isDialerRoleHeld(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(Context.ROLE_SERVICE) as RoleManager
            roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
        } else {
            val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            context.packageName == telecomManager.defaultDialerPackage
        }
    }
}
