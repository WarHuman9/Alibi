package com.example.alibi.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulationPhase
import com.example.alibi.telecom.TelecomConstants
import com.example.alibi.ui.screens.ActiveCallScreen
import com.example.alibi.ui.theme.AlibiTheme
import com.example.alibi.util.ProximityController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Dedicated lightweight Activity for lockscreen call display and FullScreenIntent launches.
 * Renders [ActiveCallScreen] for ALL call origins and states (Incoming, Outgoing, Active, Holding).
 */
class InCallActivity : ComponentActivity() {

    private var currentCallIdState by mutableStateOf<String?>(null)
    private val proximityController by lazy { ProximityController(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        updateLockscreenFlags(true)

        lifecycleScope.launch {
            CallStateManager.isBusy.collect { isBusy ->
                if (isBusy) proximityController.start() else proximityController.stop()
            }
        }

        val initialId = intent?.getStringExtra(TelecomConstants.EXTRA_CALL_ID)
        currentCallIdState = initialId
        
        val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
        Log.d("[Alibi_FSI]", "InCallActivity.onCreate: callId=$initialId, action=${intent?.action}, flags=${intent?.flags}, isKeyguardLocked=${keyguardManager?.isKeyguardLocked}, isKeyguardSecure=${keyguardManager?.isKeyguardSecure}")

        setContent {
            AlibiTheme {
                val activeCalls by CallStateManager.activeCalls.collectAsStateWithLifecycle()
                var hasObservedCallSession by remember(currentCallIdState) { mutableStateOf(false) }

                val targetId = currentCallIdState ?: activeCalls.keys.firstOrNull()

                val currentCallMeta = currentCallIdState?.let { activeCalls[it] }
                val isRinging = if (currentCallMeta != null) {
                    currentCallMeta.state == Call.STATE_RINGING || 
                    (currentCallMeta.isSimulated && currentCallMeta.phase == SimulationPhase.RINGING)
                } else {
                    intent?.getBooleanExtra(TelecomConstants.EXTRA_IS_INCOMING, true) ?: true
                }

                LaunchedEffect(isRinging) {
                    updateLockscreenFlags(isRinging)
                }

                LaunchedEffect(currentCallIdState, activeCalls) {
                    val currentId = currentCallIdState
                    if (currentId != null && activeCalls.containsKey(currentId)) {
                        hasObservedCallSession = true
                        val meta = activeCalls[currentId]
                        if (meta != null && (meta.state == Call.STATE_DISCONNECTED || meta.state == Call.STATE_DISCONNECTING)) {
                            Log.d("[Alibi_FSI]", "Call $currentId observed as disconnected. Finishing InCallActivity.")
                            finishAndRemoveTask()
                        }
                    } else if (hasObservedCallSession) {
                        Log.d("[Alibi_FSI]", "Call $currentId removed after observation. Finishing InCallActivity.")
                        finishAndRemoveTask()
                    }
                }

                // Grace period safety fallback: If initial ID was passed via Intent but session never appears in activeCalls within 1.5s
                LaunchedEffect(currentCallIdState, hasObservedCallSession) {
                    if (currentCallIdState != null && !hasObservedCallSession) {
                        delay(1500L)
                        if (!hasObservedCallSession) {
                            Log.w("[Alibi_FSI]", "Grace period expired without observing session for $currentCallIdState. Stale launch -> Finishing.")
                            finishAndRemoveTask()
                        }
                    }
                }

                // Lockscreen Back Button Interception: Trigger native PIN/Pattern unlock prompt when Back is pressed on locked device
                val isLocked = keyguardManager?.isKeyguardLocked ?: false
                BackHandler(enabled = isLocked) {
                    Log.d("[Alibi_FSI]", "Back button pressed on lockscreen. Triggering PIN/Pattern unlock prompt.")
                    requestKeyguardDismissalWithCallback()
                }

                if (targetId != null) {
                    ActiveCallScreen(callId = targetId)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val currentCallMeta = currentCallIdState?.let { CallStateManager.activeCalls.value[it] }
        val isRinging = if (currentCallMeta != null) {
            currentCallMeta.state == Call.STATE_RINGING || 
            (currentCallMeta.isSimulated && currentCallMeta.phase == SimulationPhase.RINGING)
        } else {
            intent?.getBooleanExtra(TelecomConstants.EXTRA_IS_INCOMING, true) ?: true
        }
        updateLockscreenFlags(isRinging)
        Log.d("[Alibi_FSI]", "InCallActivity.onStart: callId=$currentCallIdState, isRinging=$isRinging")
    }

    override fun onResume() {
        super.onResume()
        Log.d("[Alibi_FSI]", "InCallActivity.onResume: callId=$currentCallIdState")
    }

    override fun onPause() {
        super.onPause()
        Log.d("[Alibi_FSI]", "InCallActivity.onPause: callId=$currentCallIdState, isFinishing=$isFinishing")
    }

    override fun onStop() {
        super.onStop()
        Log.d("[Alibi_FSI]", "InCallActivity.onStop: callId=$currentCallIdState")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d("[Alibi_FSI]", "InCallActivity.onWindowFocusChanged: hasFocus=$hasFocus, callId=$currentCallIdState")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newCallId = intent.getStringExtra(TelecomConstants.EXTRA_CALL_ID)
        Log.d("[Alibi_FSI]", "InCallActivity.onNewIntent: newCallId=$newCallId")
        if (!newCallId.isNullOrBlank()) {
            currentCallIdState = newCallId
        }
    }

    private fun updateLockscreenFlags(isRinging: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(isRinging)
            } else {
                @Suppress("DEPRECATION")
                if (isRinging) {
                    window.addFlags(
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    )
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
                }
            }
            if (isRinging) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            Log.d("[Alibi_FSI]", "updateLockscreenFlags: isRinging=$isRinging")
        } catch (e: Exception) {
            Log.e("[Alibi_FSI]", "updateLockscreenFlags: Error updating lockscreen flags", e)
        }
    }

    private fun requestKeyguardDismissalWithCallback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
                keyguardManager?.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissSucceeded() {
                        Log.d("[Alibi_FSI]", "KeyguardDismissCallback: onDismissSucceeded for $currentCallIdState")
                    }

                    override fun onDismissCancelled() {
                        Log.d("[Alibi_FSI]", "KeyguardDismissCallback: onDismissCancelled for $currentCallIdState")
                    }

                    override fun onDismissError() {
                        Log.e("[Alibi_FSI]", "KeyguardDismissCallback: onDismissError for $currentCallIdState")
                    }
                })
            }
        } catch (e: Exception) {
            Log.e("[Alibi_FSI]", "requestKeyguardDismissalWithCallback: Exception requesting dismissal", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proximityController.stop()
        Log.d("[Alibi_FSI]", "InCallActivity.onDestroy: callId=$currentCallIdState")
    }

    companion object {
        private const val TAG = "InCallActivity"
    }
}
