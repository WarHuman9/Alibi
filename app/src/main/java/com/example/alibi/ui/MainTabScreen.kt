package com.example.alibi.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import com.example.alibi.ui.screens.DialerScreen
import com.example.alibi.ui.screens.SetupScreen

@Composable
fun MainTabScreen(
    initialNumber: String? = null,
    onNavigateToCall: (String) -> Unit
) {
    var activeTab by rememberSaveable { mutableStateOf("phone") }
    
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = activeTab == "phone",
                    onClick = { activeTab = "phone" },
                    icon = { Icon(Icons.Rounded.Call, contentDescription = "Phone") },
                    label = { Text("Phone") }
                )
                NavigationBarItem(
                    selected = activeTab == "simulate",
                    onClick = { activeTab = "simulate" },
                    icon = { Icon(Icons.Rounded.Settings, contentDescription = "Simulate") },
                    label = { Text("Simulate") }
                )
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            when (activeTab) {
                "phone" -> DialerScreen(initialNumber = initialNumber)
                "simulate" -> SetupScreen(onNavigateToCall = onNavigateToCall)
            }
        }
    }
}
