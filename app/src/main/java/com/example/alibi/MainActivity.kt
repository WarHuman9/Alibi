package com.example.alibi

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.alibi.ui.ActiveCallRoute
import com.example.alibi.ui.MainTabScreen
import com.example.alibi.ui.MainTabsRoute
import com.example.alibi.ui.screens.ActiveCallScreen
import com.example.alibi.ui.theme.AlibiTheme

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        CallStateManager.restoreState(this)

        val initialNumber = if (intent?.action == Intent.ACTION_DIAL || intent?.action == Intent.ACTION_VIEW) {
            intent?.data?.schemeSpecificPart
        } else null

        setContent {
            AlibiTheme {
                AlibiApp(initialNumber)
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

    LaunchedEffect(currentCall, isSimulatedCallActive, simulatedPhoneNumber) {
        if (currentCall != null || isSimulatedCallActive) {
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
                        onNavigateToCall = { number ->
                            // Navigation handled by LaunchedEffect
                        }
                    )
                } as NavEntry<androidx.navigation3.runtime.NavKey>
                is ActiveCallRoute -> NavEntry(key) {
                    ActiveCallScreen(
                        phoneNumber = key.phoneNumber
                    )
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

