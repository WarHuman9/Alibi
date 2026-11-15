package com.warhuman.alibi.ui

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object MainTabsRoute : NavKey

@Serializable
data class ActiveCallRoute(val callId: String) : NavKey {
    init {
        require(callId.isNotBlank()) { "ActiveCallRoute requires a non-blank callId" }
    }
}

@Serializable
data class ContactProfileRoute(
    val phoneNumber: String,
    val contactName: String? = null
) : NavKey {
    init {
        require(phoneNumber.isNotBlank()) { "ContactProfileRoute requires a non-blank phoneNumber" }
    }
}
