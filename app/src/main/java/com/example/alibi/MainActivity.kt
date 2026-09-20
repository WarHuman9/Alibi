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
import android.app.KeyguardManager
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.telecom.Call
import android.view.KeyEvent
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.alibi.telecom.SimulationPhase
import com.example.alibi.util.ProximityController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val proximityController by lazy { ProximityController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockscreenFlags()
        
        lifecycleScope.launch {
            CallStateManager.isBusy.collect { isBusy ->
                if (isBusy) proximityController.start() else proximityController.stop()
            }
        }
        
        // CRITICAL: Pre-register the simulation account before any call attempts.
        viewModel.onTelecomInitialization()

        intent?.data?.schemeSpecificPart?.takeIf {
            intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW
        }?.let { number ->
            viewModel.onDeeplinkReceived(number)
        }

        setContent {
            val status by viewModel.systemStatus.collectAsStateWithLifecycle()
            
            AppOnboarding(status) {
                AlibiTheme {
                    AlibiApp()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureLockscreenFlags()
        
        intent.data?.schemeSpecificPart?.takeIf {
            intent.action == Intent.ACTION_DIAL || intent.action == Intent.ACTION_VIEW
        }?.let { number ->
            viewModel.onDeeplinkReceived(number)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            val isRinging = CallStateManager.activeCalls.value.values.any {
                it.state == Call.STATE_RINGING || it.phase == SimulationPhase.RINGING
            }
            if (isRinging) {
                Log.d(TAG, "Volume button pressed during incoming call. Silencing ringtone.")
                CallStateManager.silenceRingtone()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        super.onDestroy()
        proximityController.stop()
    }

    private fun configureLockscreenFlags() {
        // MainActivity does not request lockscreen or keyguard bypass.
        // Lockscreen display is handled exclusively by InCallActivity.
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
        }

        val contactsPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            viewModel.updateContactsPermissionStatus(isGranted)
        }

        val callLogPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted -> 
            viewModel.updateCallLogPermissionStatus(isGranted)
        }

        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission()
        ) { isGranted ->
            viewModel.updateNotificationsPermissionStatus(isGranted)
        }

        val lifecycleOwner = LocalLifecycleOwner.current
        val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()

        LaunchedEffect(lifecycleState) {
            if (lifecycleState == Lifecycle.State.RESUMED) {
                viewModel.refreshStatus()
            }
        }

        // Consolidate permission chain logic while preserving the reverse sequence.
        LaunchedEffect(lifecycleState, status) {
            if (lifecycleState != Lifecycle.State.RESUMED) return@LaunchedEffect
            
            val needsNotifications = Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            
            val needsCallLog = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED
            
            val needsContacts = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED

            val needsPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED
            val needsPhoneNumbers = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) != PackageManager.PERMISSION_GRANTED
            
            val needsDialerRole = !RoleHelper.isDialerRoleHeld(context)
            val needsFullScreenIntent = !RoleHelper.canUseFullScreenIntent(context)

            Log.d("Alibi_Onboarding", "Step Check - Notifications: $needsNotifications, CallLog: $needsCallLog, Contacts: $needsContacts, Phone: ${needsPhoneState || needsPhoneNumbers}, Role: $needsDialerRole, FullScreenIntent: $needsFullScreenIntent")

            when {
                needsNotifications -> {
                    Log.d("Alibi_Onboarding", "Launching Notifications permission")
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                needsCallLog -> {
                    Log.d("Alibi_Onboarding", "Launching Call Log permission")
                    callLogPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
                }
                needsContacts -> {
                    Log.d("Alibi_Onboarding", "Launching Contacts permission")
                    contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
                }
                needsPhoneState || needsPhoneNumbers -> {
                    Log.d("Alibi_Onboarding", "Launching Phone permissions")
                    val permissions = mutableListOf(Manifest.permission.READ_PHONE_STATE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
                    }
                    phonePermissionsLauncher.launch(permissions.toTypedArray())
                }
                needsDialerRole -> {
                    Log.d("Alibi_Onboarding", "Requesting Dialer Role")
                    requestDialerRole(context, roleLauncher)
                }
                needsFullScreenIntent -> {
                    Log.d("Alibi_Onboarding", "Requesting FullScreenIntent / Lockscreen permission")
                    RoleHelper.openFullScreenIntentSettings(context)
                }
                else -> {
                    if (!status.isLegacyCleanedUp) {
                        Log.d("Alibi_Onboarding", "Onboarding complete. Cleaning up legacy.")
                        viewModel.cleanupLegacy()
                    }
                }
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
fun AlibiApp() {
    val backStack = rememberNavBackStack(MainTabsRoute)
    val activeCalls by CallStateManager.activeCalls.collectAsStateWithLifecycle()

    // Global navigation sync: Navigate to Call Screen for DIALING, RINGING, ACTIVE, or HOLDING calls
    LaunchedEffect(activeCalls) {
        val callToShow = activeCalls.values.find {
            it.state == Call.STATE_DIALING ||
            it.state == Call.STATE_RINGING ||
            it.state == Call.STATE_ACTIVE ||
            it.state == Call.STATE_HOLDING
        }

        if (callToShow != null) {
            val callId = callToShow.id
            if (callId.isNotBlank()) {
                val currentRoute = backStack.lastOrNull()
                if (currentRoute !is ActiveCallRoute || currentRoute.callId != callId) {
                    if (currentRoute is ActiveCallRoute) {
                        Log.d("AlibiApp", "Switching ActiveCallRoute from ${currentRoute.callId} to $callId")
                        backStack.removeLastOrNull()
                    } else {
                        Log.d("AlibiApp", "Navigating to ActiveCallRoute for $callId")
                    }
                    backStack.add(ActiveCallRoute(callId))
                }
            }
        } else {
            // If the map becomes empty OR all calls are DISCONNECTED, return to MainTabsRoute
            if (backStack.any { it is ActiveCallRoute }) {
                // 100ms settling debounce to allow smooth call handoffs/preemption without navigation flickering
                delay(100)
                val recheckedCall = CallStateManager.activeCalls.value.values.find {
                    it.state == Call.STATE_DIALING ||
                    it.state == Call.STATE_RINGING ||
                    it.state == Call.STATE_ACTIVE ||
                    it.state == Call.STATE_HOLDING
                }
                if (recheckedCall == null && backStack.any { it is ActiveCallRoute }) {
                    Log.d("AlibiApp", "No active or holding calls detected after 100ms debounce. Clearing backstack to MainTabsRoute.")
                    backStack.clear()
                    backStack.add(MainTabsRoute)
                }
            }
        }
    }

    val entryProvider: (NavKey) -> NavEntry<NavKey> = remember {
        { key ->
            @Suppress("UNCHECKED_CAST")
            when (key) {
                is MainTabsRoute -> NavEntry(key) {
                    MainTabScreen(
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
