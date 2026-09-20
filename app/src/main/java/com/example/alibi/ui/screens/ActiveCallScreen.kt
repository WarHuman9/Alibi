package com.example.alibi.ui.screens

import android.telecom.Call
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulationPhase
import com.example.alibi.ui.components.*
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
fun ActiveCallScreen(
    callId: String
) {
    val callInfo by remember(callId) { 
        CallStateManager.getCallMetadata(callId) 
    }.collectAsStateWithLifecycle(initialValue = null)
    
    val isMuted by CallStateManager.isMuted.collectAsStateWithLifecycle()
    val speakerOn by CallStateManager.isSpeakerOn.collectAsStateWithLifecycle()
    
    // Remember saveable properties for smooth transitions and configuration change safety
    var lastPhoneNumber by rememberSaveable { mutableStateOf("") }
    var lastDurationText by rememberSaveable { mutableStateOf("00:00") }

    if (callInfo != null) {
        val num = callInfo!!.number
        if (num.isNotBlank()) {
            lastPhoneNumber = num
        }
    }

    val callState = callInfo?.state ?: Call.STATE_DISCONNECTED
    val simulationPhase = callInfo?.phase ?: SimulationPhase.IDLE
    val isHolding = callInfo?.isHolding ?: false
    val displayPhoneNumber = callInfo?.number?.takeIf { it.isNotBlank() } ?: lastPhoneNumber
    val answerTime = callInfo?.answerTime ?: 0L

    LaunchedEffect(callState, simulationPhase) {
        Log.d("Alibi_UI", "Screen received state change: callId=$callId, callState=$callState, phase=$simulationPhase, isHolding=$isHolding")
    }

    var durationSeconds by remember { mutableLongStateOf(0L) }
    
    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    LaunchedEffect(callState, answerTime) {
        if (callState == Call.STATE_ACTIVE || callState == Call.STATE_HOLDING) {
            if (answerTime > 0) {
                while (true) {
                    val currentTime = System.currentTimeMillis()
                    durationSeconds = if (currentTime >= answerTime) (currentTime - answerTime) / 1000 else 0
                    delay(1.seconds)
                }
            }
        } else {
            durationSeconds = 0
        }
    }

    val isCloaking = CallStateManager.isCloaking
    val statusText = when (callState) {
        Call.STATE_RINGING -> "Incoming call..."
        Call.STATE_DIALING, Call.STATE_CONNECTING -> "Calling..."
        Call.STATE_ACTIVE -> if (isCloaking) "Calling..." else "Active call"
        Call.STATE_HOLDING -> "Active call"
        Call.STATE_DISCONNECTED -> "Call Ended"
        else -> "Connecting..."
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val timeText = String.format(locale, "%02d:%02d", durationSeconds / 60, durationSeconds % 60)

    if (durationSeconds > 0) {
        lastDurationText = timeText
    }

    val activeCallsMap by CallStateManager.activeCalls.collectAsStateWithLifecycle()
    val holdingCall = activeCallsMap.values.find {
        it.id != callId && (it.isHolding || it.state == Call.STATE_HOLDING)
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (callState != Call.STATE_DISCONNECTED) {
                CallActionButtons(
                    callState = callState,
                    onAnswer = { CallStateManager.answer(callId) },
                    onHangup = { CallStateManager.disconnect(callId) }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            MaterialTheme.colorScheme.surface,
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        )
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.TopCenter
            ) {
                if (holdingCall != null) {
                    MultiCallSwapBanner(
                        holdingCallName = holdingCall.name,
                        holdingCallNumber = holdingCall.number,
                        onSwap = {
                            Log.d("Alibi_UI", "MultiCallSwapBanner: Swapping active call $callId with holding call ${holdingCall.id}")
                            CallStateManager.hold(callId)
                            CallStateManager.resume(holdingCall.id)
                        }
                    )
                } else {
                    HoldBadge(
                        isHolding = isHolding,
                        onToggleHold = {
                            if (isHolding) {
                                Log.d("Alibi_UI", "HoldBadge: Resuming call $callId")
                                CallStateManager.resume(callId)
                            } else {
                                Log.d("Alibi_UI", "HoldBadge: Holding call $callId")
                                CallStateManager.hold(callId)
                            }
                        }
                    )
                }
            }

            val contentScale = if (isExpanded) 1.2f else 1f
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .scale(contentScale),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (callState == Call.STATE_RINGING || callState == Call.STATE_DIALING || (callState == Call.STATE_ACTIVE && isCloaking)) {
                        PulsingAvatar(infiniteTransition, isExpanded)
                    }
                    Surface(
                        modifier = Modifier.size(if (isExpanded) 150.dp else 100.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 8.dp
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(if (isExpanded) 36.dp else 24.dp)
                                .fillMaxSize(),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                Spacer(modifier = Modifier.height(32.dp))

                CallHeader(statusText = statusText, phoneNumber = displayPhoneNumber)

                Spacer(modifier = Modifier.height(16.dp))

                CallTimer(
                    visible = callState == Call.STATE_ACTIVE && !isCloaking,
                    timeText = timeText
                )

                CallControls(
                    visible = callState != Call.STATE_DISCONNECTED,
                    isMuted = isMuted,
                    speakerOn = speakerOn,
                    isCloaking = isCloaking,
                    onToggleMute = { CallStateManager.toggleMute() },
                    onToggleSpeaker = { CallStateManager.toggleSpeaker() }
                )

                if (callState == Call.STATE_DISCONNECTED) {
                    Text(
                        text = lastDurationText,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Light
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
