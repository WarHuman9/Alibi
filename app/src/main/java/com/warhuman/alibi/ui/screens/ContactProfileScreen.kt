package com.warhuman.alibi.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.provider.CallLog
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.warhuman.alibi.MainActivity
import com.warhuman.alibi.telecom.CallMetadata
import com.warhuman.alibi.telecom.CallStateManager
import com.warhuman.alibi.telecom.TelecomHelper
import com.warhuman.alibi.util.CallLogHelper
import com.warhuman.alibi.util.ContactResolver
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactProfileScreen(
    phoneNumber: String,
    contactName: String? = null,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isInspectionMode = androidx.compose.ui.platform.LocalInspectionMode.current
    val callLogHelper = remember { CallLogHelper.getInstance(context) }
    val telecomHelper = remember { if (!isInspectionMode) TelecomHelper(context) else null }
    val systemStatus = MainActivity.LocalSystemStatus.current
    val isBusy by CallStateManager.isBusy.collectAsStateWithLifecycle()

    val resolvedName = remember(phoneNumber, contactName) {
        val initial = contactName?.ifBlank { null }
        initial ?: ContactResolver.resolveFast(phoneNumber, null)
    }

    val displayName = resolvedName.ifBlank { phoneNumber.ifBlank { "Unknown" } }
    val showNumberSubtext = phoneNumber.isNotBlank() && !phoneNumber.equals(displayName, ignoreCase = true)

    val rawRecentCalls by if (systemStatus.isCallLogGranted && !isInspectionMode) {
        callLogHelper.getRecentCallsFlow(1000).collectAsStateWithLifecycle(null)
    } else {
        remember { mutableStateOf(emptyList()) }
    }

    val contactCallHistory = remember(rawRecentCalls, phoneNumber) {
        val targetKey = ContactResolver.normalizeKey(phoneNumber)
        rawRecentCalls?.filter { item ->
            ContactResolver.normalizeKey(item.number) == targetKey
        } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Person,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = displayName,
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                ),
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )

            if (showNumberSubtext) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = phoneNumber,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        if (!systemStatus.isPhonePermissionsGranted) {
                            Toast.makeText(context, "Phone permission required", Toast.LENGTH_LONG).show()
                            (context as? MainActivity)?.triggerRepair()
                        } else if (!isBusy) {
                            scope.launch {
                                val callId = "REAL_${UUID.randomUUID()}"
                                CallStateManager.addCall(
                                    callId, CallMetadata(
                                        id = callId,
                                        number = phoneNumber,
                                        name = displayName,
                                        isRealCall = true,
                                        state = android.telecom.Call.STATE_CONNECTING
                                    )
                                )
                                try {
                                    @SuppressLint("MissingPermission")
                                    telecomHelper?.placeRealCall(phoneNumber, null, callId)
                                } catch (e: Exception) {
                                    Log.e("ContactProfileScreen", "Failed to place call", e)
                                    CallStateManager.removeCall(callId)
                                }
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Call,
                        contentDescription = "Call",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Call", style = MaterialTheme.typography.labelLarge)
                }

                Spacer(modifier = Modifier.width(16.dp))

                Button(
                    onClick = {
                        try {
                            val smsIntent = Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse("smsto:$phoneNumber")
                            }
                            context.startActivity(smsIntent)
                        } catch (e: Exception) {
                            Log.e("ContactProfileScreen", "Failed to launch SMS app", e)
                            Toast.makeText(context, "No messaging app found", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Message,
                        contentDescription = "Message",
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Message", style = MaterialTheme.typography.labelLarge)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Call History",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.weight(1f))
                if (contactCallHistory.isNotEmpty()) {
                    Text(
                        text = "${contactCallHistory.size} entries",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 24.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            if (contactCallHistory.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No call history found for this number",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(contactCallHistory, key = { it.id }) { call ->
                        CallHistoryItem(call = call)
                    }
                }
            }
        }
    }
}

@Composable
private fun CallHistoryItem(call: CallLogHelper.CallLogItem) {
    val formatter = remember { SimpleDateFormat("MMM dd, yyyy HH:mm:ss", Locale.getDefault()) }
    val isMissed = call.type == CallLog.Calls.MISSED_TYPE || call.type == CallLog.Calls.REJECTED_TYPE

    val directionText = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming Call"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing Call"
        CallLog.Calls.MISSED_TYPE -> "Missed Call"
        CallLog.Calls.REJECTED_TYPE -> "Rejected Call"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked Call"
        else -> "Call"
    }

    val icon = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Rounded.CallReceived
        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade
        else -> Icons.AutoMirrored.Rounded.CallMissed
    }

    ListItem(
        headlineContent = {
            Text(
                text = directionText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = formatter.format(Date(call.date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Duration: ${formatProfileDuration(call.duration)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (isMissed) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isMissed) MaterialTheme.colorScheme.onErrorContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if ((call.features and CallLog.Calls.FEATURES_HD_CALL) != 0) {
                    Icon(
                        imageVector = Icons.Rounded.HighQuality,
                        contentDescription = "HD",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if ((call.features and CallLog.Calls.FEATURES_WIFI) != 0) {
                    Icon(
                        imageVector = Icons.Rounded.Wifi,
                        contentDescription = "Wi-Fi",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                if ((call.features and CallLog.Calls.FEATURES_VIDEO) != 0) {
                    Icon(
                        imageVector = Icons.Rounded.Videocam,
                        contentDescription = "Video",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    )
}

private fun formatProfileDuration(seconds: Long): String {
    return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}
