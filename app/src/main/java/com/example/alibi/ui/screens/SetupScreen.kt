package com.example.alibi.ui.screens

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.CallLog
import android.telecom.TelecomManager
import android.text.format.DateFormat
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.util.CallLogHelper
import com.example.alibi.util.RoleHelper
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(
    onNavigateToCall: (String) -> Unit,
) {
    val context = LocalContext.current
    val telecomHelper = remember { TelecomHelper(context) }
    val callLogHelper = remember { CallLogHelper.getInstance(context) }
    val is24Hour = remember(context) { DateFormat.is24HourFormat(context) }
    
    val isBusy by CallStateManager.isBusy.collectAsStateWithLifecycle()
    val busyMessage by CallStateManager.busyMessage.collectAsStateWithLifecycle()
    
    // Signal fully drawn once SetupScreen is composed
    ReportDrawnWhen { true }
    
    // Main States
    var phoneNumber by rememberSaveable { mutableStateOf("") }
    var isDialerHeld by remember { mutableStateOf(RoleHelper.isDialerRoleHeld(context)) }
    var callDirection by rememberSaveable { mutableIntStateOf(CallLog.Calls.INCOMING_TYPE) }
    var durationSeconds by rememberSaveable { mutableStateOf("60") }
    var autoAnswerDelay by rememberSaveable { mutableStateOf("5") }

    // Advanced Metadata States
    var showAdvanced by rememberSaveable { mutableStateOf(value = false) }
    var networkType by rememberSaveable { mutableIntStateOf(0) } // 0: Standard, 1: HD/5G
    var callTypeMetadata by rememberSaveable { mutableIntStateOf(0) } // 0: Voice, 1: Video
    var callOrigin by rememberSaveable { mutableIntStateOf(0) } // 0: Cellular, 1: WiFi

    val simAccounts = remember { telecomHelper.getCallCapableSims() }
    var selectedSim by remember { 
        mutableStateOf(
            simAccounts.find { it.handle.id == telecomHelper.getPreferredSimId() } 
            ?: simAccounts.firstOrNull()
        )
    }

    // Date/Time States
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val timePickerState = rememberTimePickerState(
        initialHour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
        initialMinute = Calendar.getInstance().get(Calendar.MINUTE),
        is24Hour = is24Hour
    )
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    // Static Data
    val directions = remember {
        listOf(
            "Incoming" to CallLog.Calls.INCOMING_TYPE,
            "Outgoing" to CallLog.Calls.OUTGOING_TYPE,
            "Missed" to CallLog.Calls.MISSED_TYPE
        )
    }

    val activeColor = MaterialTheme.colorScheme.primary // Using primary which is usually dark blue in default M3

    // Formatters
    val dateFormatter = remember { SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()) }
    val timeFormatter = remember(is24Hour) { 
        SimpleDateFormat(if (is24Hour) "HH:mm" else "hh:mm a", Locale.getDefault()) 
    }

    // Derived UI State
    val selectedDateText by remember {
        derivedStateOf {
            datePickerState.selectedDateMillis?.let { dateFormatter.format(Date(it)) } ?: "Select Date"
        }
    }

    val selectedTimeText by remember {
        derivedStateOf {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                set(Calendar.MINUTE, timePickerState.minute)
            }
            timeFormatter.format(cal.time)
        }
    }

    fun getSelectedTimestamp(): Long {
        val dateMillis = datePickerState.selectedDateMillis ?: System.currentTimeMillis()
        val dateCalendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = dateMillis
        }
        
        return Calendar.getInstance().apply {
            set(Calendar.YEAR, dateCalendar.get(Calendar.YEAR))
            set(Calendar.MONTH, dateCalendar.get(Calendar.MONTH))
            set(Calendar.DAY_OF_MONTH, dateCalendar.get(Calendar.DAY_OF_MONTH))
            set(Calendar.HOUR_OF_DAY, timePickerState.hour)
            set(Calendar.MINUTE, timePickerState.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }

    fun getFeatureFlags(): Int {
        var flags = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (networkType == 1) flags = flags or CallLog.Calls.FEATURES_HD_CALL
            if (callTypeMetadata == 1) flags = flags or CallLog.Calls.FEATURES_VIDEO
            if (callOrigin == 1) flags = flags or CallLog.Calls.FEATURES_WIFI
        }
        return flags
    }

    // Register PhoneAccount on composition
    LaunchedEffect(Unit) {
        telecomHelper.registerPhoneAccount()
    }

    val roleLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        isDialerHeld = RoleHelper.isDialerRoleHeld(context)
    }

    // Dialogs isolated from main scroll performance
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("OK")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimePicker) {
        TimePickerDialog(
            state = timePickerState,
            onDismiss = { showTimePicker = false },
            onConfirm = {
                showTimePicker = false
            },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (isDialerHeld && phoneNumber.isNotBlank()) {
                StartCallFAB(
                    enabled = !isBusy,
                    onClick = {
                        val customTime = getSelectedTimestamp()
                        val duration = durationSeconds.toLongOrNull() ?: 60L
                        val features = getFeatureFlags()
                        if (callDirection == CallLog.Calls.OUTGOING_TYPE) {
                            telecomHelper.startOutgoingCall(
                                phoneNumber = phoneNumber,
                                autoAnswerDelay = autoAnswerDelay.toIntOrNull() ?: 0,
                                customStartTime = customTime,
                                durationSeconds = duration,
                                mimicSimHandle = selectedSim?.handle,
                                features = features
                            )
                        } else {
                            telecomHelper.startIncomingCall(
                                phoneNumber = phoneNumber,
                                callType = callDirection,
                                customStartTime = customTime,
                                durationSeconds = duration,
                                mimicSimHandle = selectedSim?.handle,
                                features = features
                            )
                        }
                        onNavigateToCall(phoneNumber)
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 0.dp), // Removed vertical padding
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            SetupHeader()

            if (isBusy && busyMessage != null) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ),
                    modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.History, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(busyMessage!!, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp)) // Minimum spacer

            // Call Direction Segmented Button
            Text(
                text = "Call Direction",
                style = MaterialTheme.typography.labelSmall, // Shrunk
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                directions.forEachIndexed { index, (label, value) ->
                    SegmentedButton(
                        selected = callDirection == value,
                        onClick = { callDirection = value },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = directions.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = activeColor.copy(alpha = 0.15f),
                            activeContentColor = activeColor,
                            activeBorderColor = activeColor,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Text(label)
                    }
                }
            }

        Spacer(modifier = Modifier.height(8.dp)) // Reduced

        // SIM Identity Segmented Button
        if (simAccounts.isNotEmpty()) {
            Text(
                text = "SIM Identity",
                style = MaterialTheme.typography.labelSmall, // Shrunk
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                simAccounts.forEachIndexed { index, sim ->
                    SegmentedButton(
                        selected = selectedSim?.handle?.id == sim.handle.id,
                        onClick = { 
                            selectedSim = sim
                            telecomHelper.setPreferredSimId(sim.handle.id)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = simAccounts.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = activeColor.copy(alpha = 0.15f),
                            activeContentColor = activeColor,
                            activeBorderColor = activeColor,
                            inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(sim.label.take(8))
                            sim.address?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 7.sp),
                                    color = if (selectedSim?.handle?.id == sim.handle.id) activeColor else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp)) // Reduced
        }

            PhoneNumberField(
                value = phoneNumber,
                onValueChange = { phoneNumber = it }
            )

            Spacer(modifier = Modifier.height(8.dp)) // Reduced

            DurationFields(
                durationSeconds = durationSeconds,
                onDurationChange = { durationSeconds = it },
                showAutoAnswer = (callDirection == CallLog.Calls.OUTGOING_TYPE),
                autoAnswerDelay = autoAnswerDelay,
                onAutoAnswerChange = { autoAnswerDelay = it },
                isMissed = (callDirection == CallLog.Calls.MISSED_TYPE)
            )

            Spacer(modifier = Modifier.height(12.dp)) // Reduced
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            
            CustomStartTimeSection(
                dateText = selectedDateText,
                timeText = selectedTimeText,
                onShowDatePicker = { showDatePicker = true },
                onShowTimePicker = { showTimePicker = true }
            )

            Spacer(modifier = Modifier.height(8.dp)) // Reduced
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            AdvancedDetailsSection(
                expanded = showAdvanced,
                onExpandedChange = { enabled ->
                    showAdvanced = enabled
                    if (enabled) {
                        val snapshot = telecomHelper.getNetworkSnapshot()
                        networkType = if (snapshot.isHdCapable) 1 else 0
                        callOrigin = if (snapshot.isWifiCallingActive) 1 else 0
                        callTypeMetadata = 0 // Default to Voice
                    }
                },
                networkType = networkType,
                onNetworkChange = { networkType = it },
                callType = callTypeMetadata,
                onCallTypeChange = { callTypeMetadata = it },
                origin = callOrigin,
                onOriginChange = { callOrigin = it }
            )

            Spacer(modifier = Modifier.height(8.dp))

            DialerRoleSection(
                isDialerHeld = isDialerHeld,
                onRequestRole = {
                    val activity = context as? Activity
                    if (activity != null) {
                        requestRole(activity, roleLauncher)
                    }
                },
                onRegisterOnly = {
                    val duration = durationSeconds.toLongOrNull() ?: 0L
                    val timestamp = getSelectedTimestamp()
                    val features = getFeatureFlags()
                    callLogHelper.insertCallLog(
                        phoneNumber = phoneNumber,
                        duration = duration,
                        timestamp = timestamp,
                        callType = callDirection,
                        simHandle = selectedSim?.handle,
                        features = features
                    )
                    android.widget.Toast.makeText(context, "Call registered in log", android.widget.Toast.LENGTH_SHORT).show()
                },
                canRegister = phoneNumber.isNotBlank()
            )
        }
    }
}

@Composable
private fun SetupHeader() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Simulate Call",
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun PhoneNumberField(value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text("Phone Number") },
        placeholder = { Text("e.g. +1234567890") },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        singleLine = true,
        shape = MaterialTheme.shapes.large
    )
}

@Composable
private fun DurationFields(
    durationSeconds: String,
    onDurationChange: (String) -> Unit,
    showAutoAnswer: Boolean,
    autoAnswerDelay: String,
    onAutoAnswerChange: (String) -> Unit,
    isMissed: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = durationSeconds,
            onValueChange = onDurationChange,
            label = { Text(if (isMissed) "Ringing Time" else "Duration") },
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            placeholder = { if (isMissed) Text("sec") }
        )

        if (showAutoAnswer) {
            OutlinedTextField(
                value = autoAnswerDelay,
                onValueChange = onAutoAnswerChange,
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
private fun CustomStartTimeSection(
    dateText: String,
    timeText: String,
    onShowDatePicker: () -> Unit,
    onShowTimePicker: () -> Unit
) {
    Column {
        Text(
            text = "Call Start Time",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            OutlinedButton(
                onClick = onShowDatePicker,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(dateText)
            }
            OutlinedButton(
                onClick = onShowTimePicker,
                modifier = Modifier.weight(1f),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(timeText)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AdvancedDetailsSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    networkType: Int,
    onNetworkChange: (Int) -> Unit,
    callType: Int,
    onCallTypeChange: (Int) -> Unit,
    origin: Int,
    onOriginChange: (Int) -> Unit
) {
    // Constraint Logic
    val isStandardNetwork = networkType == 0
    val isWifiOrigin = origin == 1
    val isVideoCall = callType == 1
    val activeColor = MaterialTheme.colorScheme.primary

    // If WiFi or Video is selected, Network must be HD (1).
    // If Network is Standard (0), WiFi and Video must be disabled.
    
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!expanded) },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Advanced Details",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Switch(
                checked = expanded,
                onCheckedChange = onExpandedChange
            )
        }

        if (expanded) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // Network Type
            Text("Network Type", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val standardEnabled = !isWifiOrigin && !isVideoCall
                SegmentedButton(
                    selected = networkType == 0,
                    onClick = { onNetworkChange(0) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = standardEnabled,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = activeColor.copy(alpha = 0.15f),
                        activeContentColor = activeColor,
                        activeBorderColor = activeColor,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledInactiveContentColor = Color.Gray.copy(alpha = 0.5f),
                        disabledInactiveBorderColor = Color.LightGray.copy(alpha = 0.5f)
                    )
                ) { Text("Standard") }
                SegmentedButton(
                    selected = networkType == 1,
                    onClick = { onNetworkChange(1) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = activeColor.copy(alpha = 0.15f),
                        activeContentColor = activeColor,
                        activeBorderColor = activeColor,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("HD/5G") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Call Type
            Text("Call Type", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = callType == 0,
                    onClick = { onCallTypeChange(0) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = activeColor.copy(alpha = 0.15f),
                        activeContentColor = activeColor,
                        activeBorderColor = activeColor,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Voice") }
                SegmentedButton(
                    selected = callType == 1,
                    onClick = { 
                        onCallTypeChange(1)
                        onNetworkChange(1) // Force HD
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = !isStandardNetwork,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = activeColor.copy(alpha = 0.15f),
                        activeContentColor = activeColor,
                        activeBorderColor = activeColor,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledInactiveContentColor = Color.Gray.copy(alpha = 0.5f),
                        disabledInactiveBorderColor = Color.LightGray.copy(alpha = 0.5f)
                    )
                ) { Text("Video") }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Call Origin
            Text("Call Origin", style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.height(4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = origin == 0,
                    onClick = { onOriginChange(0) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = activeColor.copy(alpha = 0.15f),
                        activeContentColor = activeColor,
                        activeBorderColor = activeColor,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text("Cellular") }
                SegmentedButton(
                    selected = origin == 1,
                    onClick = { 
                        onOriginChange(1)
                        onNetworkChange(1) // Force HD
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = !isStandardNetwork,
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = activeColor.copy(alpha = 0.15f),
                        activeContentColor = activeColor,
                        activeBorderColor = activeColor,
                        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        disabledInactiveContentColor = Color.Gray.copy(alpha = 0.5f),
                        disabledInactiveBorderColor = Color.LightGray.copy(alpha = 0.5f)
                    )
                ) { Text("WiFi") }
            }
        }
    }
}

@Composable
private fun DialerRoleSection(
    isDialerHeld: Boolean,
    onRequestRole: () -> Unit,
    onRegisterOnly: () -> Unit,
    canRegister: Boolean
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!isDialerHeld) {
            Button(
                onClick = onRequestRole,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium
            ) {
                Text("Set as Default Dialer")
            }
        } else {
            OutlinedButton(
                onClick = onRegisterOnly,
                modifier = Modifier.fillMaxWidth(),
                enabled = canRegister,
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Rounded.History, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Register Only")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = if (isDialerHeld) "App is ready to handle calls" else "App needs dialer role to simulate calls",
            color = if (isDialerHeld) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium
        )
        
        Spacer(modifier = Modifier.height(16.dp)) // Reduced bottom padding
    }
}

@Composable
private fun StartCallFAB(enabled: Boolean, onClick: () -> Unit) {
    LargeFloatingActionButton(
        onClick = { if (enabled) onClick() },
        containerColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (enabled) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    ) {
        Icon(
            imageVector = Icons.Rounded.Call,
            contentDescription = "Start Simulation",
            modifier = Modifier.size(36.dp)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimePickerDialog(
    state: TimePickerState,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
            modifier = Modifier
                .width(IntrinsicSize.Min)
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Select Time",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 20.dp)
                )
                TimePicker(state = state)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    TextButton(onClick = onConfirm) {
                        Text("OK")
                    }
                }
            }
        }
    }
}

private fun requestRole(activity: Activity, launcher: androidx.activity.result.ActivityResultLauncher<Intent>) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = activity.getSystemService(Context.ROLE_SERVICE) as RoleManager
        if (roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            launcher.launch(intent)
        }
    } else {
        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, activity.packageName)
        launcher.launch(intent)
    }
}
