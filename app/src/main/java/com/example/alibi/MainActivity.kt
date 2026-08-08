package com.example.alibi

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.alibi.service.CallNotificationService
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.ui.ActiveCallRoute
import com.example.alibi.ui.MainTabScreen
import com.example.alibi.ui.MainTabsRoute
import com.example.alibi.ui.screens.ActiveCallScreen
import com.example.alibi.ui.theme.AlibiTheme
import com.example.alibi.util.RoleHelper

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // --- System Hard Reset ---
        // Clean up any leaked state/notifications from previous app instances or crashes.
        CallStateManager.forceClearState(this)
        stopService(Intent(this, CallNotificationService::class.java))
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.cancelAll() 

        val initialNumber = intent?.data?.schemeSpecificPart?.takeIf {
            intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW
        }

        setContent {
            AppOnboarding {
                AlibiTheme {
                    AlibiApp(initialNumber)
                }
            }
        }
    }

    /**
     * Component to handle sequential permission and role onboarding.
     */
    @Composable
    private fun AppOnboarding(content: @Composable () -> Unit) {
        val context = this
        val callLogPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { Log.d(TAG, "Call log permission handled") }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { 
            Log.d(TAG, "Notification permission handled")
            callLogPermissionLauncher.launch(android.Manifest.permission.READ_CALL_LOG)
        }

        val roleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { 
            Log.d(TAG, "Dialer role handled")
            if (Build.VERSION.SDK_INT >= 33) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            } else {
                callLogPermissionLauncher.launch(android.Manifest.permission.READ_CALL_LOG)
            }
        }

        LaunchedEffect(Unit) {
            // Sequence: Dialer Role -> Notifications -> Call Log
            if (!RoleHelper.isDialerRoleHeld(context)) {
                requestDialerRole(context, roleLauncher)
            } else {
                if (Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    callLogPermissionLauncher.launch(android.Manifest.permission.READ_CALL_LOG)
                }
            }
        }

        content()
    }

    companion object {
        private const val TAG = "MainActivity"

        private fun requestDialerRole(activity: Activity, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
                    if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
                        launcher.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
                    }
                } else {
                    val intent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
                    launcher.launch(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting dialer role", e)
            }
        }
    }
}

@Composable
fun AlibiApp(initialNumber: String? = null) {
    val backStack = rememberNavBackStack(MainTabsRoute)
    val currentCall by CallStateManager.currentCall.collectAsStateWithLifecycle()
    val isSimulatedCallActive by CallStateManager.isSimulatedCallActive.collectAsStateWithLifecycle()
    val simulatedPhoneNumber by CallStateManager.simulatedPhoneNumber.collectAsStateWithLifecycle()

    // Global navigation sync: If a call becomes active, force navigation to Call Screen
    LaunchedEffect(currentCall, isSimulatedCallActive, simulatedPhoneNumber) {
        val isActive = currentCall != null || isSimulatedCallActive
        if (isActive) {
            val phoneNumber = currentCall?.details?.handle?.schemeSpecificPart
                ?: simulatedPhoneNumber
                ?: "Unknown"
            if (backStack.isEmpty() || backStack.last() !is ActiveCallRoute) {
                backStack.add(ActiveCallRoute(phoneNumber))
            }
        } else {
            if (backStack.isNotEmpty() && backStack.last() is ActiveCallRoute) {
                backStack.removeLastOrNull()
            }
        }
    }

    val entryProvider: (androidx.navigation3.runtime.NavKey) -> NavEntry<androidx.navigation3.runtime.NavKey> = remember {
        { key ->
            @Suppress("UNCHECKED_CAST")
            when (key) {
                is MainTabsRoute -> NavEntry(key) {
                    MainTabScreen(
                        initialNumber = initialNumber,
                        onNavigateToCall = { /* Handled by global effect */ }
                    )
                } as NavEntry<androidx.navigation3.runtime.NavKey>
                is ActiveCallRoute -> NavEntry(key) {
                    ActiveCallScreen(phoneNumber = key.phoneNumber)
                } as NavEntry<androidx.navigation3.runtime.NavKey>
                else -> error("Unknown key: $key")
            }
        }
    }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = entryProvider
    )
}
