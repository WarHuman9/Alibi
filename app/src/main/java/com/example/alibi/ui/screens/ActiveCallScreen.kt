package com.example.alibi.ui.screens

import android.telecom.Call
import android.provider.CallLog
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.SimulationPhase
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.platform.LocalLocale

@Composable
fun ActiveCallScreen(
    phoneNumber: String
) {
    val state by CallStateManager.state.collectAsState()
    
    val currentCallId = state.currentCallId
    val activeCalls = state.activeCalls
    val callInfo = activeCalls[currentCallId] ?: activeCalls.values.lastOrNull()
    
    val callState = callInfo?.state ?: Call.STATE_DISCONNECTED
    val simulationPhase = callInfo?.phase ?: SimulationPhase.IDLE
    val currentCall = callInfo?.call
    val isMuted = state.isMuted
    val speakerOn = state.isSpeakerOn
    val isHolding = state.isHolding
    
    // Task 15: Log state changes for debugging
    LaunchedEffect(callState, simulationPhase) {
        android.util.Log.d("Alibi_UI", "Screen received state change: callState=$callState, phase=$simulationPhase")
    }
    
    // Task 15: Observe currentCall metadata directly for reliability
    val displayPhoneNumber = remember(callInfo, phoneNumber) {
        callInfo?.number ?: phoneNumber
    }
    
    val answerTime = callInfo?.answerTime ?: 0L
    var durationSeconds by remember { mutableLongStateOf(0L) }
    
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED

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
    val statusText = when {
        callState == Call.STATE_RINGING -> "Incoming call..."
        callState == Call.STATE_DIALING || callState == Call.STATE_CONNECTING -> "Calling..."
        callState == Call.STATE_ACTIVE && isCloaking -> "Calling..."
        callState == Call.STATE_ACTIVE -> "Active call"
        callState == Call.STATE_HOLDING -> "Active call"
        callState == Call.STATE_DISCONNECTED -> "Call Ended"
        else -> "Connecting..."
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val timeText = String.format(LocalLocale.current.platformLocale, "%02d:%02d", durationSeconds / 60, durationSeconds % 60)

    // Capture the last non-zero duration to prevent flickering on disconnect
    var lastDurationText by remember { mutableStateOf("00:00") }
    if (durationSeconds > 0) {
        lastDurationText = timeText
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Show Answer button if it's Ringing (Incoming)
                val showAnswer = callState == Call.STATE_RINGING

                if (showAnswer) {
                    LargeFloatingActionButton(
                        onClick = { CallStateManager.answer() },
                        containerColor = Color(0xFF4CAF50), // Material Green
                        contentColor = Color.White,
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Check,
                            contentDescription = "Answer Call",
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
                
                LargeFloatingActionButton(
                    onClick = { CallStateManager.disconnect() },
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                    shape = CircleShape
                ) {
                    Icon(
                        imageVector = Icons.Rounded.CallEnd,
                        contentDescription = "End Call",
                        modifier = Modifier.size(36.dp)
                    )
                }
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
                ),
            contentAlignment = Alignment.Center
        ) {
            // Task 11: High-priority On Hold Badge (Persistent and Non-Clipped)
            AnimatedVisibility(
                visible = isHolding,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 32.dp)
                    .zIndex(10f) // Highest priority
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 8.dp,
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.MicOff,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "CALL ON HOLD",
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 1.2.sp
                            ),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            val contentScale = if (isExpanded) 1.2f else 1f
            Column(
                modifier = Modifier.scale(contentScale),
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

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = displayPhoneNumber,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 36.sp
                    ),
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Timer - only visible in ACTIVE phase and not cloaked
                AnimatedVisibility(
                    visible = callState == Call.STATE_ACTIVE && !isCloaking,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Light
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Call Controls - visible in DIALING, RINGING, ACTIVE, or HOLDING
                AnimatedVisibility(
                    visible = callState != Call.STATE_DISCONNECTED,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(modifier = Modifier.height(48.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            FilledTonalIconButton(
                                onClick = { CallStateManager.toggleMute() },
                                enabled = !isCloaking,
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (isMuted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isMuted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                    contentDescription = if (isMuted) "Unmute" else "Mute"
                                )
                            }
                            
                            FilledTonalIconButton(
                                onClick = { CallStateManager.toggleSpeaker() },
                                colors = IconButtonDefaults.filledTonalIconButtonColors(
                                    containerColor = if (speakerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (speakerOn) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = if (speakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                    contentDescription = "Speaker"
                                )
                            }
                        }
                    }
                }

                // End Call final duration - only in DISCONNECTED
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

@Composable
private fun PulsingAvatar(infiniteTransition: InfiniteTransition, isExpanded: Boolean) {
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    Box(
        modifier = Modifier
            .size(if (isExpanded) 180.dp else 120.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    )
}
