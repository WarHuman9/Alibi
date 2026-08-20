package com.example.alibi

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.telecom.TelecomManager
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.ui.ActiveCallRoute
import com.example.alibi.ui.MainTabScreen
import com.example.alibi.ui.MainTabsRoute
import com.example.alibi.ui.MainViewModel
import com.example.alibi.ui.SystemStatus
import com.example.alibi.ui.screens.ActiveCallScreen
import com.example.alibi.ui.theme.AlibiTheme
import com.example.alibi.util.RoleHelper
import android.Manifest
import android.annotation.SuppressLint
import android.app.role.RoleManager
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // --- Smart System Reset ---
        // Only wipe state if no call is active. This prevents the "Timer Chip" crash.
        if (!CallStateManager.isBusy.value) {
            Log.d("MainActivity", "Initializing system...")
        }

        // CRITICAL: Pre-register the simulation account before any call attempts.
        viewModel.onTelecomInitialization()

        val initialNumber = intent?.data?.schemeSpecificPart?.takeIf {
            intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW
        }

        setContent {
            val status by viewModel.systemStatus.collectAsStateWithLifecycle()
            
            AppOnboarding(status) {
                AlibiTheme {
                    AlibiApp(initialNumber)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Redirection handled by reactive navigation in AlibiApp
        setIntent(intent)
    }

    /**
     * Component to handle sequential permission and role onboarding.
     * Implements the "Reverse Chain": Notifications -> Call Log -> Dialer Role.
     */
    @Composable
    private fun AppOnboarding(status: SystemStatus, content: @Composable () -> Unit) {
        val context = this
        
        val roleLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { 
            val isHeld = RoleHelper.isDialerRoleHeld(context)
            viewModel.updateRoleStatus(isHeld)
        }

        val phonePermissionsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            val isGranted = results.values.all { it }
            viewModel.updatePhonePermissionsStatus(isGranted)
            
            // Final step: Dialer Role
            if (!RoleHelper.isDialerRoleHeld(context)) {
                requestDialerRole(context, roleLauncher)
            }
        }

        val callLogPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted -> 
            viewModel.updateCallLogPermissionStatus(isGranted)
            // Next step: Phone Permissions
            val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            phonePermissionsLauncher.launch(permissions.toTypedArray())
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            viewModel.updateNotificationsPermissionStatus(isGranted)
            // Next step: Call Log
            callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

        LaunchedEffect(lifecycleState) {
            if (lifecycleState == Lifecycle.State.RESUMED) {
                viewModel.refreshStatus()
            }
        }

        // Consolidate permission chain logic while preserving the reverse sequence.
        LaunchedEffect(Unit) {
            val needsNotifications = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            
            val needsCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
            
            val needsPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
            val needsPhoneNumbers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED
            
            val needsDialerRole = !RoleHelper.isDialerRoleHeld(context)

            when {
                needsNotifications -> notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                needsCallLog -> callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                needsPhoneState || needsPhoneNumbers -> {
                    val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
                    }
                    phonePermissionsLauncher.launch(permissions.toTypedArray())
                }
                needsDialerRole -> requestDialerRole(context, roleLauncher)
                else -> viewModel.cleanupLegacy()
            }
        }

        CompositionLocalProvider(LocalSystemStatus provides status) {
            content()
        }
    }

    fun triggerRepair() {
        viewModel.triggerRepair()
    }

    companion object {
        val LocalSystemStatus = staticCompositionLocalOf { SystemStatus() }
        private const val TAG = "MainActivity"

        private fun requestDialerRole(activity: Activity, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = activity.getSystemService(ROLE_SERVICE) as RoleManager
                    if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                        launcher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    }
                } else {
                    val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                        .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
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
    val activeCalls by CallStateManager.activeCalls.collectAsStateWithLifecycle()

    // Global navigation sync: Navigate to Call Screen ONLY for DIALING, RINGING, or ACTIVE calls
    LaunchedEffect(activeCalls) {
        val callToShow = activeCalls.values.find {
            it.state == android.telecom.Call.STATE_DIALING ||
            it.state == android.telecom.Call.STATE_RINGING ||
            it.state == android.telecom.Call.STATE_ACTIVE
        }

        if (callToShow != null) {
            val callId = callToShow.id
            if (backStack.lastOrNull() !is ActiveCallRoute) {
                backStack.add(ActiveCallRoute(callId))
            }
        } else {
            // If the map becomes empty OR all calls are DISCONNECTED, return to Setup
            if (backStack.any { it is ActiveCallRoute }) {
                Log.d("AlibiApp", "No active calls detected. Clearing backstack to SetupRoute.")
                // Navigation 3: popUpTo(SetupRoute) { inclusive = true } equivalent:
                // Clear the backstack and ensure MainTabsRoute (Setup) is the only entry.
                backStack.clear()
                backStack.add(MainTabsRoute)
            }
        }
    }

    val entryProvider: (NavKey) -> NavEntry<NavKey> = remember {
        { key ->
            @Suppress("UNCHECKED_CAST")
            when (key) {
                is MainTabsRoute -> NavEntry(key) {
                    MainTabScreen(
                        initialNumber = initialNumber,
                        onNavigateToCall = { _ ->
                            // Manual navigation from Setup/Dialer is now largely reactive
                            // but we keep the callback for consistency if needed.
                        }
                    )
                } as NavEntry<NavKey>
                is ActiveCallRoute -> NavEntry(key) {
                    ActiveCallScreen(callId = key.callId)
                } as NavEntry<NavKey>
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
