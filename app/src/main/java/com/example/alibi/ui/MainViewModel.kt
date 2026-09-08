package com.example.alibi.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.util.RoleHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.seconds

data class SystemStatus(
    val isDialerRoleHeld: Boolean = false,
    val isCallLogGranted: Boolean = false,
    val isNotificationsGranted: Boolean = false,
    val isPhonePermissionsGranted: Boolean = false,
    val isContactsPermissionGranted: Boolean = false,
    val isCallPermissionGranted: Boolean = false,
    val isRegistryWarmedUp: Boolean = false,
    val isRepairing: Boolean = false,
    val isLegacyCleanedUp: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val context = getApplication<Application>()
    private val telecomHelper = TelecomHelper(context)

    private val _systemStatus = MutableStateFlow(SystemStatus())
    val systemStatus = _systemStatus.asStateFlow()

    private val _deeplinkNumber = MutableStateFlow<String?>(null)
    val deeplinkNumber = _deeplinkNumber.asStateFlow()

    init {
        // Initial registration attempt
        viewModelScope.launch {
            telecomHelper.registerPhoneAccount()
        }
    }

    @SuppressLint("MissingPermission")
    fun refreshStatus() {
        viewModelScope.launch {
            val status = withContext(Dispatchers.IO) {
                val isRoleHeld = RoleHelper.isDialerRoleHeld(context)
                val isCallLogGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
                val isNotificationsGranted = if (Build.VERSION.SDK_INT >= 33) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else true
                
                val hasPhoneState = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                val hasPhoneNumbers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_NUMBERS) == PackageManager.PERMISSION_GRANTED
                } else true
                val isPhoneGranted = hasPhoneState && hasPhoneNumbers
                
                val isContactsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
                val isCallPermissionGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

                val isWarmedUp = telecomHelper.isAccountRegistered()
                
                SystemStatus(
                    isDialerRoleHeld = isRoleHeld,
                    isCallLogGranted = isCallLogGranted,
                    isNotificationsGranted = isNotificationsGranted,
                    isPhonePermissionsGranted = isPhoneGranted,
                    isContactsPermissionGranted = isContactsGranted,
                    isCallPermissionGranted = isCallPermissionGranted,
                    isRegistryWarmedUp = isWarmedUp,
                )
            }

            _systemStatus.update { status }

            if (status.isDialerRoleHeld) {
                telecomHelper.cleanupLegacyAccounts()
            }
        }
    }

    fun triggerRepair() {
        if (_systemStatus.value.isRepairing) return
        
        _systemStatus.update { it.copy(isRepairing = true) }
        
        viewModelScope.launch {
            try {
                repeat(15) {
                    telecomHelper.registerPhoneAccount()
                    val isWarmed = telecomHelper.isAccountRegistered()
                    if (isWarmed) {
                        _systemStatus.update { it.copy(isRegistryWarmedUp = true, isRepairing = false) }
                        return@launch
                    }
                    delay(1.seconds)
                }
            } finally {
                _systemStatus.update { it.copy(isRepairing = false) }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun updateRoleStatus(isHeld: Boolean) {
        _systemStatus.update { it.copy(isDialerRoleHeld = isHeld) }
        if (isHeld) {
            viewModelScope.launch {
                telecomHelper.cleanupLegacyAccounts()
            }
        }
    }

    fun updatePhonePermissionsStatus(isGranted: Boolean) {
        _systemStatus.update { it.copy(isPhonePermissionsGranted = isGranted) }
        if (isGranted) {
            viewModelScope.launch {
                telecomHelper.registerPhoneAccount()
            }
        }
    }

    fun updateContactsPermissionStatus(isGranted: Boolean) {
        _systemStatus.update { it.copy(isContactsPermissionGranted = isGranted) }
    }

    fun updateCallPermissionStatus(isGranted: Boolean) {
        _systemStatus.update { it.copy(isCallPermissionGranted = isGranted) }
    }

    fun updateCallLogPermissionStatus(isGranted: Boolean) {
        _systemStatus.update { it.copy(isCallLogGranted = isGranted) }
    }

    fun updateNotificationsPermissionStatus(isGranted: Boolean) {
        _systemStatus.update { it.copy(isNotificationsGranted = isGranted) }
    }

    fun onTelecomInitialization() {
        viewModelScope.launch {
            telecomHelper.registerPhoneAccount()
        }
    }
    
    @SuppressLint("MissingPermission")
    fun cleanupLegacy() {
        if (_systemStatus.value.isLegacyCleanedUp) return
        
        viewModelScope.launch {
            telecomHelper.cleanupLegacyAccounts()
            _systemStatus.update { it.copy(isLegacyCleanedUp = true) }
        }
    }

    fun onDeeplinkReceived(number: String) {
        _deeplinkNumber.value = number
    }

    fun consumeDeeplink() {
        _deeplinkNumber.value = null
    }
}
