package com.example.alibi.ui.screens

import android.telecom.Call
import android.provider.CallLog
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.alibi.telecom.CallStateManager
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

@Composable
fun ActiveCallScreen(
    phoneNumber: String
) {
    val callState by CallStateManager.callState.collectAsState()
    val currentCall by CallStateManager.currentCall.collectAsState()
    val isRealCall by CallStateManager.isRealCall.collectAsState()
    val logCallType by CallStateManager.logCallType.collectAsState()
    val answerTime by CallStateManager.answerTime.collectAsState()
    val isMuted by CallStateManager.isMuted.collectAsState()
    val speakerOn by CallStateManager.speakerOn.collectAsState()
    
    // Get phone number from real call if available
    val displayPhoneNumber = remember(currentCall, phoneNumber) {
        currentCall?.details?.handle?.schemeSpecificPart ?: phoneNumber
    }
    
    var durationSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(callState, answerTime) {
        if (callState == Call.STATE_ACTIVE && answerTime > 0) {
            while (true) {
                val currentTime = System.currentTimeMillis()
                durationSeconds = if (currentTime >= answerTime) (currentTime - answerTime) / 1000 else 0
                delay(1.seconds)
            }
        } else {
            durationSeconds = 0
        }
    }

    val statusText = when (callState) {
        Call.STATE_RINGING -> "Incoming Call..."
        Call.STATE_DIALING -> "Calling..."
        Call.STATE_ACTIVE -> "Active Call"
        Call.STATE_HOLDING -> "On Hold"
        Call.STATE_DISCONNECTED -> "Call Ended"
        else -> "Connecting..."
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")

    val timeText = String.format(Locale.getDefault(), "%02d:%02d", durationSeconds / 60, durationSeconds % 60)

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
                // For Missed simulated calls, we already hide it. 
                // For real calls, we always show it when ringing.
                val showAnswer = callState == Call.STATE_RINGING && (isRealCall || logCallType != CallLog.Calls.MISSED_TYPE)

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
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (callState == Call.STATE_RINGING || callState == Call.STATE_DIALING) {
                        PulsingAvatar(infiniteTransition)
                    }
                    Surface(
                        modifier = Modifier.size(100.dp),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        tonalElevation = 8.dp
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Call,
                            contentDescription = null,
                            modifier = Modifier
                                .padding(24.dp)
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

                if (callState == Call.STATE_ACTIVE) {
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.displaySmall.copy(
                            fontWeight = FontWeight.Light
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(48.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        IconButton(onClick = { CallStateManager.toggleMute() }) {
                            Icon(
                                imageVector = if (isMuted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                                contentDescription = "Mute",
                                tint = if (isMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                        }
                        IconButton(onClick = { CallStateManager.toggleSpeaker() }) {
                            Icon(
                                imageVector = if (speakerOn) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                contentDescription = "Speaker",
                                tint = if (speakerOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PulsingAvatar(infiniteTransition: InfiniteTransition) {
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
            .size(120.dp)
            .scale(pulseScale)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
    )
}
