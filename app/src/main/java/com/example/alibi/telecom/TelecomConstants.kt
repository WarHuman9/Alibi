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
    
    // Internal Phone Account ID
    const val SIMULATED_ACCOUNT_ID = "SimulatedCallAccount"
    const val SIMULATED_ACCOUNT_LABEL = "Alibi Simulated Call"
}
