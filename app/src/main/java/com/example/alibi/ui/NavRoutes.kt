package com.example.alibi.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object MainTabsRoute : NavKey

@Serializable
data class ActiveCallRoute(val phoneNumber: String) : NavKey
