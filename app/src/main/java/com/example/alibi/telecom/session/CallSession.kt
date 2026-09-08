package com.example.alibi.telecom.session

import com.example.alibi.telecom.CallMetadata
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstracts common actions for both real and simulated calls.
 */
interface CallSession {
    val id: String
    val metadata: StateFlow<CallMetadata>
    
    fun answer()
    fun hangup()
    fun hold()
    fun resume()
}
