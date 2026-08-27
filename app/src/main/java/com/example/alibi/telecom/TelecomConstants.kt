package com.example.alibi.telecom

/**
 * Centralized constants for Telecom Bundle Extras and internal identification.
 * This ensures consistency between [TelecomHelper] and [com.example.alibi.service.SimulatedConnectionService].
 */
object TelecomConstants {
    const val EXTRA_CALL_TYPE = "EXTRA_CALL_TYPE"
    const val EXTRA_CUSTOM_START_TIME = "EXTRA_CUSTOM_START_TIME"
    const val EXTRA_INTENDED_DURATION = "EXTRA_INTENDED_DURATION"
    const val EXTRA_MIMIC_SIM_HANDLE = "EXTRA_MIMIC_SIM_HANDLE"
    const val EXTRA_CALL_FEATURES = "EXTRA_CALL_FEATURES"
    const val EXTRA_AUTO_ANSWER_DELAY = "EXTRA_AUTO_ANSWER_DELAY"
    const val EXTRA_CONNECTION_ID = "EXTRA_CONNECTION_ID"
    const val EXTRA_ALIBI_CALL_ID = "EXTRA_ALIBI_CALL_ID"
    const val EXTRA_REAL_CALL = "EXTRA_REAL_CALL"

    // Notification Extras
    const val EXTRA_PHONE_NUMBER = "extra_phone_number"
    const val EXTRA_NAME = "extra_name"
    const val EXTRA_CALL_ID = "extra_call_id"
    const val EXTRA_IS_INCOMING = "extra_is_incoming"
    const val EXTRA_IS_MISSED = "extra_is_missed"
    const val EXTRA_IS_DIALING = "extra_is_dialing"
    const val EXTRA_IS_SIMULATED = "extra_is_simulated"
    const val EXTRA_START_TIME = "extra_start_time"

    // Actions
    const val ACTION_STOP_SERVICE = "com.example.alibi.action.STOP_NOTIFICATION_SERVICE"
    const val ACTION_HANGUP = "com.example.alibi.ACTION_HANGUP"
    const val ACTION_ANSWER = "com.example.alibi.ACTION_ANSWER"

    // Action Identifiers for unique PendingIntents
    const val REQUEST_CODE_ANSWER = 1000
    const val REQUEST_CODE_HANGUP = 2000
    const val REQUEST_CODE_CONTENT = 3000
    
    // Internal Phone Account ID - Keep this stable to prevent system cache issues.
    const val SIMULATED_ACCOUNT_ID = "AlibiSimulatedAccount_Stable"
    const val SIMULATED_ACCOUNT_LABEL = "Alibi Simulation"

    // Service & Thread Identifiers
    const val WAKE_LOCK_TAG = "Alibi:CallWakeLock"
    const val CALL_SERVICE_BG_THREAD = "Alibi_CallService_Bg"
    const val NOTIFICATION_TAG = "Alibi_Notification"
    const val STATE_TAG = "Alibi_State"

    // Legacy IDs for one-time cleanup
    val LEGACY_ACCOUNT_IDS = listOf(
        "SimulatedCallAccount",
        "AlibiSimulatedAccount_v2",
        "AlibiSimulatedAccount"
    )
}
