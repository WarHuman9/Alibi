package com.example.alibi.ui

import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telecom.Call
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomConstants
import com.example.alibi.ui.screens.ActiveCallScreen
import com.example.alibi.ui.theme.AlibiTheme
import kotlinx.coroutines.delay

/**
 * Dedicated lightweight Activity for lockscreen call display and FullScreenIntent launches.
 * Free from onboarding, main tabs backstack, or heavy ViewModel overhead.
 */
class IncomingCallActivity : ComponentActivity() {

    private var currentCallIdState by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureLockscreenFlags()

        val initialId = intent?.getStringExtra(TelecomConstants.EXTRA_CALL_ID)
        currentCallIdState = initialId
        Log.d(TAG, "onCreate: callId=$initialId")

        setContent {
            AlibiTheme {
                val activeCalls by CallStateManager.activeCalls.collectAsStateWithLifecycle()
                var hasObservedCallSession by remember(currentCallIdState) { mutableStateOf(false) }

                val targetId = currentCallIdState ?: activeCalls.keys.firstOrNull()

                LaunchedEffect(currentCallIdState, activeCalls) {
                    val currentId = currentCallIdState
                    if (currentId != null && activeCalls.containsKey(currentId)) {
                        hasObservedCallSession = true
                        val meta = activeCalls[currentId]
                        if (meta != null && (meta.state == Call.STATE_DISCONNECTED || meta.state == Call.STATE_DISCONNECTING)) {
                            Log.d(TAG, "Call $currentId observed as disconnected. Finishing IncomingCallActivity.")
                            finish()
                        }
                    } else if (hasObservedCallSession) {
                        Log.d(TAG, "Call $currentId removed after observation. Finishing IncomingCallActivity.")
                        finish()
                    }
                }

                // Grace period safety fallback: If initial ID was passed via Intent but session never appears in activeCalls within 1.5s
                LaunchedEffect(currentCallIdState) {
                    if (currentCallIdState != null && !hasObservedCallSession) {
                        delay(1500L)
                        if (!hasObservedCallSession) {
                            Log.w(TAG, "Grace period expired without observing session for $currentCallIdState. Stale launch -> Finishing.")
                            finish()
                        }
                    }
                }

                if (targetId != null) {
                    ActiveCallScreen(callId = targetId)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        configureLockscreenFlags()
        val newCallId = intent.getStringExtra(TelecomConstants.EXTRA_CALL_ID)
        Log.d(TAG, "onNewIntent: newCallId=$newCallId")
        if (!newCallId.isNullOrBlank()) {
            currentCallIdState = newCallId
        }
    }

    private fun configureLockscreenFlags() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(KEYGUARD_SERVICE) as? KeyguardManager
                keyguardManager?.requestDismissKeyguard(this, null)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                )
            }
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d(TAG, "configureLockscreenFlags: Successfully set lockscreen & keyguard flags")
        } catch (e: Exception) {
            Log.e(TAG, "configureLockscreenFlags: Error setting lockscreen flags", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }

    companion object {
        private const val TAG = "IncomingCallActivity"
    }
}
