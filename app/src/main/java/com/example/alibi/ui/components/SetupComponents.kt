package com.example.alibi.ui.components

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CallLog
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.ui.SystemStatus
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SetupHeader() {
    Text(
        text = "Simulate Call",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
fun SystemStatusDashboard(status: SystemStatus, onRepair: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("System Integration", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(
                    imageVector = if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            
            AnimatedVisibility(visible = expanded) {
                Column {
                    Spacer(Modifier.height(8.dp))
                    
                    StatusRow("Default Dialer Role", status.isDialerRoleHeld)
                    StatusRow("Call Log Access", status.isCallLogGranted)
                    StatusRow("Notification Access", status.isNotificationsGranted)
                    StatusRow("Registry Warmed Up", status.isRegistryWarmedUp)

                    if (!status.isRegistryWarmedUp && status.isDialerRoleHeld) {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (status.isRepairing) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    "Verifying registry... Please stay in-app.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Icon(Icons.Rounded.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Registration pending... Do not close app.", 
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = onRepair) { Text("Repair") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusRow(label: String, isOk: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Icon(
            imageVector = if (isOk) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = if (isOk) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
        )
    }
}

@Composable
fun BusyBanner(message: String) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer, 
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        ), 
        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text, 
        style = MaterialTheme.typography.labelSmall, 
        color = MaterialTheme.colorScheme.primary, 
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    )
}

@Composable
fun DirectionPicker(current: Int, onSelected: (Int) -> Unit) {
    val directions = listOf(
        "Incoming" to CallLog.Calls.INCOMING_TYPE, 
        "Outgoing" to CallLog.Calls.OUTGOING_TYPE, 
        "Missed" to CallLog.Calls.MISSED_TYPE
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        directions.forEachIndexed { index, (label, value) ->
            SegmentedButton(
                selected = current == value, 
                onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index, directions.size),
                colors = segmentedColors()
            ) { Text(label) }
        }
    }
}

@Composable
fun SimPicker(accounts: List<TelecomHelper.SimAccount>, selected: TelecomHelper.SimAccount?, onSelected: (TelecomHelper.SimAccount) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        accounts.forEachIndexed { index, sim ->
            SegmentedButton(
                selected = selected?.handle?.id == sim.handle.id, 
                onClick = { onSelected(sim) },
                shape = SegmentedButtonDefaults.itemShape(index, accounts.size),
                colors = segmentedColors()
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(sim.label.take(8))
                    sim.address?.let { Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 7.sp)) }
                }
            }
        }
    }
}

@Composable
fun PhoneNumberField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value, 
        onValueChange = onValueChange, 
        label = { Text("Phone Number") }, 
        modifier = Modifier.fillMaxWidth(), 
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), 
        singleLine = true, 
        shape = MaterialTheme.shapes.large
    )
}

@Composable
fun DurationFields(
    duration: String, 
    onDurationChange: (String) -> Unit, 
    showDelay: Boolean, 
    delay: String, 
    onDelayChange: (String) -> Unit, 
    isMissed: Boolean
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = duration,
            onValueChange = onDurationChange,
            label = { Text(if (isMissed) "Ringing Time" else "Duration") },
            placeholder = { Text("Infinite") },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            supportingText = { if (duration.isBlank() || duration == "0") Text("Infinite duration") }
        )
        if (showDelay) {
            OutlinedTextField(
                value = delay, 
                onValueChange = onDelayChange, 
                label = { Text("Delay") }, 
                modifier = Modifier.weight(1f), 
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), 
                singleLine = true, 
                shape = MaterialTheme.shapes.large
            )
        }
    }
}

@Composable
fun CustomStartTimeSection(
    dateText: String, 
    timeText: String, 
    seconds: Int, 
    onSecondsChange: (Int) -> Unit, 
    onShowDatePicker: () -> Unit, 
    onShowTimePicker: () -> Unit, 
    onRefresh: () -> Unit
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Call Start Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onRefresh, Modifier.size(24.dp)) { 
                Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) 
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onShowDatePicker, Modifier.weight(1.5f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 8.dp)) { 
                Text(dateText, maxLines = 1, softWrap = false) 
            }
            OutlinedButton(onClick = onShowTimePicker, Modifier.weight(1f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 4.dp)) { 
                Text(timeText, maxLines = 1, softWrap = false) 
            }
            OutlinedTextField(
                value = seconds.toString().padStart(2, '0'), 
                onValueChange = { 
                    val v = it.take(2).filter { c -> c.isDigit() }.toIntOrNull() ?: 0
                    onSecondsChange(v.coerceIn(0, 59)) 
                },
                label = { Text("Sec", style = MaterialTheme.typography.labelSmall) }, 
                modifier = Modifier.width(64.dp), 
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), 
                singleLine = true, 
                shape = MaterialTheme.shapes.medium, 
                textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun AdvancedDetailsSection(
    expanded: Boolean, 
    onExpandedChange: (Boolean) -> Unit, 
    networkType: Int, 
    onNetworkChange: (Int) -> Unit, 
    callType: Int, 
    onCallTypeChange: (Int) -> Unit, 
    origin: Int, 
    onOriginChange: (Int) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }, 
            verticalAlignment = Alignment.CenterVertically, 
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Advanced Details", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Switch(checked = expanded, onCheckedChange = onExpandedChange)
        }
        if (expanded) {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Network Type")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = networkType == 0, 
                    onClick = { onNetworkChange(0) }, 
                    shape = SegmentedButtonDefaults.itemShape(0, 2), 
                    enabled = (origin == 0 && callType == 0), 
                    colors = segmentedColors()
                ) { Text("Standard") }
                SegmentedButton(
                    selected = networkType == 1, 
                    onClick = { onNetworkChange(1) }, 
                    shape = SegmentedButtonDefaults.itemShape(1, 2), 
                    colors = segmentedColors()
                ) { Text("HD/5G") }
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("Call Type")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = callType == 0, 
                    onClick = { onCallTypeChange(0) }, 
                    shape = SegmentedButtonDefaults.itemShape(0, 2), 
                    colors = segmentedColors()
                ) { Text("Voice") }
                SegmentedButton(
                    selected = callType == 1, 
                    onClick = { onCallTypeChange(1); onNetworkChange(1) }, 
                    shape = SegmentedButtonDefaults.itemShape(1, 2), 
                    enabled = networkType == 1, 
                    colors = segmentedColors()
                ) { Text("Video") }
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("Call Origin")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = origin == 0, 
                    onClick = { onOriginChange(0) }, 
                    shape = SegmentedButtonDefaults.itemShape(0, 2), 
                    colors = segmentedColors()
                ) { Text("Cellular") }
                SegmentedButton(
                    selected = origin == 1, 
                    onClick = { onOriginChange(1); onNetworkChange(1) }, 
                    shape = SegmentedButtonDefaults.itemShape(1, 2), 
                    enabled = networkType == 1, 
                    colors = segmentedColors()
                ) { Text("WiFi") }
            }
        }
    }
}

@Composable
fun DialerRoleSection(isDialerHeld: Boolean, onRequestRole: () -> Unit, onRegisterOnly: () -> Unit, canRegister: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!isDialerHeld) {
            Button(onClick = onRequestRole, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Set as Default Dialer") }
        } else {
            OutlinedButton(onClick = onRegisterOnly, Modifier.fillMaxWidth(), enabled = canRegister, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.History, null); Spacer(Modifier.width(8.dp)); Text("Register Only")
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = if (isDialerHeld) "App is ready to handle calls" else "App needs dialer role to simulate calls", 
            color = if (isDialerHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, 
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
fun StartCallFAB(enabled: Boolean, onClick: () -> Unit) {
    LargeFloatingActionButton(
        onClick = { if (enabled) onClick() }, 
        containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, 
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    ) {
        Icon(Icons.Rounded.Call, "Start Simulation", Modifier.size(36.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(state: TimePickerState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.width(IntrinsicSize.Min).padding(16.dp)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Select Time", style = MaterialTheme.typography.labelMedium, modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp))
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = onConfirm) { Text("OK") }
                }
            }
        }
    }
}

@Composable
fun segmentedColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    activeContentColor = MaterialTheme.colorScheme.primary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

fun requestDialerRole(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
        if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
            launcher.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
        }
    } else {
        launcher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName))
    }
}
