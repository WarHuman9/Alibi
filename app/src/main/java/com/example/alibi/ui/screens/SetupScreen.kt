package com.example.alibi.ui.screens

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.window.core.layout.WindowWidthSizeClass
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.util.CallLogHelper
import com.example.alibi.util.RoleHelper
import kotlinx.coroutines.delay
import com.example.alibi.MainActivity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * Main Setup/Simulate screen. Configures parameters for new simulated calls.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onNavigateToCall: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telecomHelper = remember { TelecomHelper(context) }
    val callLogHelper = remember { CallLogHelper.getInstance(context) }
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    
    val systemStatus = MainActivity.LocalSystemStatus.current
    val isBusy by CallStateManager.isBusy.collectAsStateWithLifecycle()
    val busyMessage by CallStateManager.busyMessage.collectAsStateWithLifecycle()
    
    ReportDrawnWhen { true }
    
    // --- Main States ---
    var phoneNumber by rememberSaveable { mutableStateOf("") }
    var isDialerHeld by remember { mutableStateOf(RoleHelper.isDialerRoleHeld(context)) }
    var callDirection by rememberSaveable { mutableIntStateOf(CallLog.Calls.INCOMING_TYPE) }
    var durationSeconds by rememberSaveable { mutableStateOf("") }
    var autoAnswerDelay by rememberSaveable { mutableStateOf("5") }

    // Advanced Metadata
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var networkType by rememberSaveable { mutableIntStateOf(0) } // 0: Std, 1: HD
    var callTypeMetadata by rememberSaveable { mutableIntStateOf(0) } // 0: Voice, 1: Video
    var callOrigin by rememberSaveable { mutableIntStateOf(0) } // 0: Cell, 1: Wi-Fi

    // SIM Selection
    val simAccounts = remember { mutableStateListOf<TelecomHelper.SimAccount>() }
    var selectedSim by remember { mutableStateOf<TelecomHelper.SimAccount?>(null) }

    // --- Live Timing logic ---
    var isTimeManuallySet by rememberSaveable { mutableStateOf(false) }
    var selectedSeconds by rememberSaveable { mutableIntStateOf(Calendar.getInstance().get(Calendar.SECOND)) }

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val timePickerState = rememberTimePickerState(
        initialHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        initialMinute = Calendar.getInstance().get(Calendar.MINUTE),
        is24Hour = is24Hour
    )

    LaunchedEffect(isTimeManuallySet) {
        if (!isTimeManuallySet) {
            while (true) {
                val now = Calendar.getInstance()
                datePickerState.selectedDateMillis = now.timeInMillis
                timePickerState.hour = now.get(Calendar.HOUR_OF_DAY)
                timePickerState.minute = now.get(Calendar.MINUTE)
                selectedSeconds = now.get(Calendar.SECOND)
                delay(1.seconds)
            }
        }
    }

    // --- Lifecycle Sync ---
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycleState) {
        isDialerHeld = RoleHelper.isDialerRoleHeld(context)
        val accounts = telecomHelper.getCallCapableSims()
        simAccounts.clear()
        simAccounts.addAll(accounts)
        if (selectedSim == null) {
            selectedSim = simAccounts.find { it.handle.id == telecomHelper.getPreferredSimId() } ?: simAccounts.firstOrNull()
        }

        // Automatic Network Detection (Smart Defaults)
        if (lifecycleState == Lifecycle.State.RESUMED) {
            val snapshot = telecomHelper.getNetworkSnapshot()
            if (!showAdvanced) {
                networkType = if (snapshot.isHdCapable) 1 else 0
                callOrigin = if (snapshot.isWifiCallingActive) 1 else 0
            }
        }
    }

    val isReady = systemStatus.isDialerRoleHeld && 
                 systemStatus.isCallLogGranted && 
                 systemStatus.isNotificationsGranted && 
                 systemStatus.isRegistryWarmedUp

    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val isExpanded = windowSizeClass.windowWidthSizeClass == WindowWidthSizeClass.EXPANDED

    // --- Internal Helpers ---
    fun getSelectedTimestamp(): Long = Calendar.getInstance().apply {
        // Use the system default calendar for both date and time to avoid UTC shifts
        val selectedDateMillis = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
        val tempCal = Calendar.getInstance().apply { timeInMillis = selectedDateMillis }
        
        set(Calendar.YEAR, tempCal.get(Calendar.YEAR))
        set(Calendar.MONTH, tempCal.get(Calendar.MONTH))
        set(Calendar.DAY_OF_MONTH, tempCal.get(Calendar.DAY_OF_MONTH))
        set(Calendar.HOUR_OF_DAY, timePickerState.hour)
        set(Calendar.MINUTE, timePickerState.minute)
        set(Calendar.SECOND, selectedSeconds)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun getFeatureFlags(): Int {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (networkType == 1) flags = flags or CallLog.Calls.FEATURES_HD_CALL
            if (callTypeMetadata == 1) flags = flags or CallLog.Calls.FEATURES_VIDEO
            if (callOrigin == 1) flags = flags or CallLog.Calls.FEATURES_WIFI
        }
        return flags
    }

    // --- UI Logic ---
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        isDialerHeld = RoleHelper.isDialerRoleHeld(context)
    }

    // Dialog Rendering
    if (showDatePicker) {
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { showDatePicker = false }) { Text("OK") } }) {
            DatePicker(state = datePickerState)
        }
    }
    if (showTimePicker) {
        TimePickerDialog(state = timePickerState, onDismiss = { showTimePicker = false }, onConfirm = { showTimePicker = false })
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (isReady && phoneNumber.isNotBlank()) {
                StartCallFAB(enabled = !isBusy) {
                    val customTime = getSelectedTimestamp()
                    val duration = if (durationSeconds.isBlank() || durationSeconds == "0") 0L else durationSeconds.toLongOrNull() ?: 0L
                    val features = getFeatureFlags()
                    scope.launch {
                        if (callDirection == CallLog.Calls.OUTGOING_TYPE) {
                            telecomHelper.startOutgoingCall(phoneNumber, autoAnswerDelay.toIntOrNull() ?: 0, customTime, duration, selectedSim?.handle, features)
                        } else {
                            telecomHelper.startIncomingCall(phoneNumber, callDirection, autoAnswerDelay.toIntOrNull() ?: 0, customTime, duration, selectedSim?.handle, features)
                        }
                    }
                    onNavigateToCall(phoneNumber)
                }
            }
        }
    ) { padding ->
        Row(
            modifier = Modifier.padding(padding).fillMaxSize().padding(horizontal = if (isExpanded) 32.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Left Column (Status and Main Controls)
            Column(
                modifier = Modifier
                    .weight(if (isExpanded) 1f else 1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp)
            ) {
                SetupHeader()

                SystemStatusDashboard(systemStatus) {
                    (context as? MainActivity)?.triggerRepair()
                }

                if (isBusy && busyMessage != null) BusyBanner(busyMessage!!)

                if (!isExpanded) {
                    SetupForm(
                        callDirection = callDirection,
                        onCallDirectionChange = { callDirection = it },
                        simAccounts = simAccounts,
                        selectedSim = selectedSim,
                        onSimSelected = { 
                            selectedSim = it
                            telecomHelper.setPreferredSimId(it.handle.id)
                        },
                        phoneNumber = phoneNumber,
                        onPhoneNumberChange = { phoneNumber = it },
                        durationSeconds = durationSeconds,
                        onDurationChange = { durationSeconds = it },
                        autoAnswerDelay = autoAnswerDelay,
                        onDelayChange = { autoAnswerDelay = it },
                        datePickerState = datePickerState,
                        timePickerState = timePickerState,
                        selectedSeconds = selectedSeconds,
                        onSecondsChange = { selectedSeconds = it; isTimeManuallySet = true },
                        onShowDatePicker = { showDatePicker = true; isTimeManuallySet = true },
                        onShowTimePicker = { showTimePicker = true; isTimeManuallySet = true },
                        onRefreshTime = {
                            isTimeManuallySet = false
                            val now = Calendar.getInstance()
                            datePickerState.selectedDateMillis = now.timeInMillis
                            timePickerState.hour = now.get(Calendar.HOUR_OF_DAY)
                            timePickerState.minute = now.get(Calendar.MINUTE)
                            selectedSeconds = now.get(Calendar.SECOND)
                            scope.launch { 
                                val accounts = telecomHelper.getCallCapableSims()
                                simAccounts.clear()
                                simAccounts.addAll(accounts)
                            }
                        },
                        showAdvanced = showAdvanced,
                        onAdvancedExpandedChange = { expanded ->
                            showAdvanced = expanded
                            if (expanded) {
                                scope.launch {
                                    val snap = telecomHelper.getNetworkSnapshot()
                                    networkType = if (snap.isHdCapable) 1 else 0
                                    callOrigin = if (snap.isWifiCallingActive) 1 else 0
                                }
                            }
                        },
                        networkType = networkType,
                        onNetworkChange = { networkType = it },
                        callTypeMetadata = callTypeMetadata,
                        onCallTypeChange = { callTypeMetadata = it },
                        callOrigin = callOrigin,
                        onOriginChange = { callOrigin = it },
                        isDialerHeld = isDialerHeld,
                        onRequestRole = { (context as? Activity)?.let { requestDialerRole(it, roleLauncher) } },
                        onRegisterOnly = {
                            scope.launch {
                                callLogHelper.insertCallLog(phoneNumber, durationSeconds.toLongOrNull() ?: 0L, getSelectedTimestamp(), callDirection, selectedSim?.handle, getFeatureFlags())
                                Toast.makeText(context, "Call registered in log", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                } else {
                    // In expanded mode, we might want some content on the left and some on the right.
                    // For now, let's just keep the header and status on the left.
                    Spacer(Modifier.height(16.dp))
                    Text("Ready to simulate secure calls. Configure your parameters on the right.", style = MaterialTheme.typography.bodyLarge)
                }
            }

            // Right Column (Form in expanded mode)
            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp)
                ) {
                    SetupForm(
                        callDirection = callDirection,
                        onCallDirectionChange = { callDirection = it },
                        simAccounts = simAccounts,
                        selectedSim = selectedSim,
                        onSimSelected = { 
                            selectedSim = it
                            telecomHelper.setPreferredSimId(it.handle.id)
                        },
                        phoneNumber = phoneNumber,
                        onPhoneNumberChange = { phoneNumber = it },
                        durationSeconds = durationSeconds,
                        onDurationChange = { durationSeconds = it },
                        autoAnswerDelay = autoAnswerDelay,
                        onDelayChange = { autoAnswerDelay = it },
                        datePickerState = datePickerState,
                        timePickerState = timePickerState,
                        selectedSeconds = selectedSeconds,
                        onSecondsChange = { selectedSeconds = it; isTimeManuallySet = true },
                        onShowDatePicker = { showDatePicker = true; isTimeManuallySet = true },
                        onShowTimePicker = { showTimePicker = true; isTimeManuallySet = true },
                        onRefreshTime = {
                            isTimeManuallySet = false
                            val now = Calendar.getInstance()
                            datePickerState.selectedDateMillis = now.timeInMillis
                            timePickerState.hour = now.get(Calendar.HOUR_OF_DAY)
                            timePickerState.minute = now.get(Calendar.MINUTE)
                            selectedSeconds = now.get(Calendar.SECOND)
                        },
                        showAdvanced = showAdvanced,
                        onAdvancedExpandedChange = { expanded ->
                            showAdvanced = expanded
                            if (expanded) {
                                scope.launch {
                                    val snap = telecomHelper.getNetworkSnapshot()
                                    networkType = if (snap.isHdCapable) 1 else 0
                                    callOrigin = if (snap.isWifiCallingActive) 1 else 0
                                }
                            }
                        },
                        networkType = networkType,
                        onNetworkChange = { networkType = it },
                        callTypeMetadata = callTypeMetadata,
                        onCallTypeChange = { callTypeMetadata = it },
                        callOrigin = callOrigin,
                        onOriginChange = { callOrigin = it },
                        isDialerHeld = isDialerHeld,
                        onRequestRole = { (context as? Activity)?.let { requestDialerRole(it, roleLauncher) } },
                        onRegisterOnly = {
                            scope.launch {
                                callLogHelper.insertCallLog(phoneNumber, durationSeconds.toLongOrNull() ?: 0L, getSelectedTimestamp(), callDirection, selectedSim?.handle, getFeatureFlags())
                                Toast.makeText(context, "Call registered in log", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SetupForm(
    callDirection: Int,
    onCallDirectionChange: (Int) -> Unit,
    simAccounts: List<TelecomHelper.SimAccount>,
    selectedSim: TelecomHelper.SimAccount?,
    onSimSelected: (TelecomHelper.SimAccount) -> Unit,
    phoneNumber: String,
    onPhoneNumberChange: (String) -> Unit,
    durationSeconds: String,
    onDurationChange: (String) -> Unit,
    autoAnswerDelay: String,
    onDelayChange: (String) -> Unit,
    datePickerState: DatePickerState,
    timePickerState: TimePickerState,
    selectedSeconds: Int,
    onSecondsChange: (Int) -> Unit,
    onShowDatePicker: () -> Unit,
    onShowTimePicker: () -> Unit,
    onRefreshTime: () -> Unit,
    showAdvanced: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    networkType: Int,
    onNetworkChange: (Int) -> Unit,
    callTypeMetadata: Int,
    onCallTypeChange: (Int) -> Unit,
    callOrigin: Int,
    onOriginChange: (Int) -> Unit,
    isDialerHeld: Boolean,
    onRequestRole: () -> Unit,
    onRegisterOnly: () -> Unit
) {
    val is24Hour = timePickerState.is24hour
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormatter = remember(is24Hour) { SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm a", Locale.getDefault()) }

    Column {
        SectionTitle("Call Direction")
        DirectionPicker(current = callDirection, onSelected = onCallDirectionChange)

        Spacer(Modifier.height(8.dp))

        if (simAccounts.isNotEmpty()) {
            SectionTitle("SIM Identity")
            SimPicker(accounts = simAccounts, selected = selectedSim, onSelected = onSimSelected)
            Spacer(Modifier.height(8.dp))
        }

        PhoneNumberField(value = phoneNumber, onValueChange = onPhoneNumberChange)

        Spacer(Modifier.height(8.dp))

        DurationFields(
            duration = durationSeconds, onDurationChange = onDurationChange,
            showDelay = (callDirection == CallLog.Calls.OUTGOING_TYPE),
            delay = autoAnswerDelay, onDelayChange = onDelayChange,
            isMissed = (callDirection == CallLog.Calls.MISSED_TYPE)
        )

        Spacer(Modifier.height(12.dp))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        
        CustomStartTimeSection(
            dateText = datePickerState.selectedDateMillis?.let { dateFormatter.format(Date(it)) } ?: "Select Date",
            timeText = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, timePickerState.hour); set(Calendar.MINUTE, timePickerState.minute) }.let { timeFormatter.format(it.time) },
            seconds = selectedSeconds,
            onSecondsChange = onSecondsChange,
            onShowDatePicker = onShowDatePicker,
            onShowTimePicker = onShowTimePicker,
            onRefresh = onRefreshTime
        )

        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        AdvancedDetailsSection(
            expanded = showAdvanced,
            onExpandedChange = onAdvancedExpandedChange,
            networkType = networkType, onNetworkChange = onNetworkChange,
            callType = callTypeMetadata, onCallTypeChange = onCallTypeChange,
            origin = callOrigin, onOriginChange = onOriginChange
        )

        Spacer(Modifier.height(8.dp))

        DialerRoleSection(
            isDialerHeld = isDialerHeld,
            onRequestRole = onRequestRole,
            onRegisterOnly = onRegisterOnly,
            canRegister = phoneNumber.isNotBlank()
        )
    }
}

// --- Sub-Components ---

@Composable
private fun SystemStatusDashboard(status: MainActivity.SystemStatus, onRepair: () -> Unit) {
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
private fun StatusRow(label: String, isOk: Boolean) {
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
private fun SetupHeader() {
    Text("Simulate Call", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun BusyBanner(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer), modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.History, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
}

@Composable
private fun DirectionPicker(current: Int, onSelected: (Int) -> Unit) {
    val directions = listOf("Incoming" to CallLog.Calls.INCOMING_TYPE, "Outgoing" to CallLog.Calls.OUTGOING_TYPE, "Missed" to CallLog.Calls.MISSED_TYPE)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        directions.forEachIndexed { index, (label, value) ->
            SegmentedButton(
                selected = current == value, onClick = { onSelected(value) },
                shape = SegmentedButtonDefaults.itemShape(index, directions.size),
                colors = segmentedColors()
            ) { Text(label) }
        }
    }
}

@Composable
private fun SimPicker(accounts: List<TelecomHelper.SimAccount>, selected: TelecomHelper.SimAccount?, onSelected: (TelecomHelper.SimAccount) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        accounts.forEachIndexed { index, sim ->
            SegmentedButton(
                selected = selected?.handle?.id == sim.handle.id, onClick = { onSelected(sim) },
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
private fun PhoneNumberField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onValueChange, label = { Text("Phone Number") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true, shape = MaterialTheme.shapes.large)
}

@Composable
private fun DurationFields(duration: String, onDurationChange: (String) -> Unit, showDelay: Boolean, delay: String, onDelayChange: (String) -> Unit, isMissed: Boolean) {
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
        if (showDelay) OutlinedTextField(value = delay, onValueChange = onDelayChange, label = { Text("Delay") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = MaterialTheme.shapes.large)
    }
}

@Composable
private fun CustomStartTimeSection(dateText: String, timeText: String, seconds: Int, onSecondsChange: (Int) -> Unit, onShowDatePicker: () -> Unit, onShowTimePicker: () -> Unit, onRefresh: () -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Call Start Time", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            IconButton(onClick = onRefresh, Modifier.size(24.dp)) { Icon(Icons.Rounded.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)) }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onShowDatePicker, Modifier.weight(1.5f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(dateText, maxLines = 1, softWrap = false) }
            OutlinedButton(onClick = onShowTimePicker, Modifier.weight(1f), shape = MaterialTheme.shapes.medium, contentPadding = PaddingValues(horizontal = 4.dp)) { Text(timeText, maxLines = 1, softWrap = false) }
            OutlinedTextField(
                value = seconds.toString().padStart(2, '0'), onValueChange = { val v = it.take(2).filter { c -> c.isDigit() }.toIntOrNull() ?: 0; onSecondsChange(v.coerceIn(0, 59)) },
                label = { Text("Sec", style = MaterialTheme.typography.labelSmall) }, modifier = Modifier.width(64.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, shape = MaterialTheme.shapes.medium, textStyle = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedDetailsSection(expanded: Boolean, onExpandedChange: (Boolean) -> Unit, networkType: Int, onNetworkChange: (Int) -> Unit, callType: Int, onCallTypeChange: (Int) -> Unit, origin: Int, onOriginChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Advanced Details", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Switch(checked = expanded, onCheckedChange = onExpandedChange)
        }
        if (expanded) {
            Spacer(Modifier.height(16.dp))
            SectionTitle("Network Type")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = networkType == 0, onClick = { onNetworkChange(0) }, shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = (origin == 0 && callType == 0), colors = segmentedColors()) { Text("Standard") }
                SegmentedButton(selected = networkType == 1, onClick = { onNetworkChange(1) }, shape = SegmentedButtonDefaults.itemShape(1, 2), colors = segmentedColors()) { Text("HD/5G") }
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("Call Type")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = callType == 0, onClick = { onCallTypeChange(0) }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = segmentedColors()) { Text("Voice") }
                SegmentedButton(selected = callType == 1, onClick = { onCallTypeChange(1); onNetworkChange(1) }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = networkType == 1, colors = segmentedColors()) { Text("Video") }
            }
            Spacer(Modifier.height(12.dp))
            SectionTitle("Call Origin")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = origin == 0, onClick = { onOriginChange(0) }, shape = SegmentedButtonDefaults.itemShape(0, 2), colors = segmentedColors()) { Text("Cellular") }
                SegmentedButton(selected = origin == 1, onClick = { onOriginChange(1); onNetworkChange(1) }, shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = networkType == 1, colors = segmentedColors()) { Text("WiFi") }
            }
        }
    }
}

@Composable
private fun DialerRoleSection(isDialerHeld: Boolean, onRequestRole: () -> Unit, onRegisterOnly: suspend () -> Unit, canRegister: Boolean) {
    val scope = rememberCoroutineScope()
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!isDialerHeld) {
            Button(onClick = onRequestRole, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) { Text("Set as Default Dialer") }
        } else {
            OutlinedButton(onClick = { scope.launch { onRegisterOnly() } }, Modifier.fillMaxWidth(), enabled = canRegister, shape = MaterialTheme.shapes.medium) {
                Icon(Icons.Rounded.History, null); Spacer(Modifier.width(8.dp)); Text("Register Only")
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(if (isDialerHeld) "App is ready to handle calls" else "App needs dialer role to simulate calls", color = if (isDialerHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StartCallFAB(enabled: Boolean, onClick: () -> Unit) {
    LargeFloatingActionButton(onClick = { if (enabled) onClick() }, containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)) {
        Icon(Icons.Rounded.Call, "Start Simulation", Modifier.size(36.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(state: TimePickerState, onDismiss: () -> Unit, onConfirm: () -> Unit) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun segmentedColors() = SegmentedButtonDefaults.colors(
    activeContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    activeContentColor = MaterialTheme.colorScheme.primary,
    activeBorderColor = MaterialTheme.colorScheme.primary,
    inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
)

private fun requestDialerRole(activity: Activity, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as android.app.role.RoleManager
        if (roleManager.isRoleAvailable(android.app.role.RoleManager.ROLE_DIALER)) {
            launcher.launch(roleManager.createRequestRoleIntent(android.app.role.RoleManager.ROLE_DIALER))
        }
    } else {
        launcher.launch(Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName))
    }
}
