package com.example.alibi.ui.screens

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Build
import android.provider.CallLog
import android.text.format.DateFormat
import android.widget.Toast
import androidx.activity.compose.ReportDrawnWhen
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.MainActivity
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.telecom.TelecomPrefs
import com.example.alibi.ui.components.*
import com.example.alibi.util.CallLogHelper
import com.example.alibi.util.RoleHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.seconds

/**
 * Isolated ticker component to prevent full-screen recomposition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LiveTicker(
    isTimeManuallySet: Boolean,
    datePickerState: DatePickerState,
    timePickerState: TimePickerState,
    onSecondsChange: (Int) -> Unit
) {
    if (!isTimeManuallySet) {
        LaunchedEffect(Unit) {
            while (true) {
                val now = Calendar.getInstance()
                datePickerState.selectedDateMillis = now.timeInMillis
                timePickerState.hour = now.get(Calendar.HOUR_OF_DAY)
                timePickerState.minute = now.get(Calendar.MINUTE)
                onSecondsChange(now.get(Calendar.SECOND))
                delay(1.seconds)
            }
        }
    }
}

/**
 * Main Setup/Simulate screen. Configures parameters for new simulated calls.
 */
@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupScreen(onNavigateToCall: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telecomHelper = remember { TelecomHelper(context) }
    val telecomPrefs = remember { TelecomPrefs(context) }
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

    LiveTicker(
        isTimeManuallySet = isTimeManuallySet,
        datePickerState = datePickerState,
        timePickerState = timePickerState,
        onSecondsChange = { selectedSeconds = it }
    )

    // --- Lifecycle Sync ---
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    LaunchedEffect(lifecycleState) {
        isDialerHeld = RoleHelper.isDialerRoleHeld(context)
        val accounts = telecomHelper.getCallCapableSims()
        simAccounts.clear()
        simAccounts.addAll(accounts)
        if (selectedSim == null) {
            val persisted = telecomPrefs.getPersistedSimHandle()
            selectedSim = if (persisted != null) {
                simAccounts.find { it.handle == persisted }
            } else {
                simAccounts.find { it.handle.id == telecomHelper.getPreferredSimId() }
            } ?: simAccounts.firstOrNull()
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

    val isReady by remember(systemStatus) {
        derivedStateOf {
            systemStatus.isDialerRoleHeld && 
            systemStatus.isCallLogGranted && 
            systemStatus.isNotificationsGranted && 
            systemStatus.isRegistryWarmedUp
        }
    }

    val windowAdaptiveInfo = currentWindowAdaptiveInfo()
    val isExpanded = windowAdaptiveInfo.windowSizeClass.isWidthAtLeastBreakpoint(androidx.window.core.layout.WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)

    // --- Internal Helpers ---
    fun getSelectedTimestamp(): Long = Calendar.getInstance().apply {
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

    val featureFlags by remember(networkType, callTypeMetadata, callOrigin) {
        derivedStateOf {
            var flags = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (networkType == 1) flags = flags or CallLog.Calls.FEATURES_HD_CALL
                if (callTypeMetadata == 1) flags = flags or CallLog.Calls.FEATURES_VIDEO
                if (callOrigin == 1) flags = flags or CallLog.Calls.FEATURES_WIFI
            }
            flags
        }
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

    val formContent = @Composable {
        SetupForm(
            callDirection = callDirection,
            onCallDirectionChange = { callDirection = it },
            simAccounts = simAccounts,
            selectedSim = selectedSim,
            onSimSelected = { 
                selectedSim = it
                telecomHelper.setPreferredSimId(it.handle.id)
                telecomPrefs.setPersistedSimHandle(it.handle)
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
                    val customTime = getSelectedTimestamp()
                    callLogHelper.insertCallLog(
                        phoneNumber, 
                        durationSeconds.toLongOrNull() ?: 0L, 
                        customTime, 
                        callDirection, 
                        selectedSim?.handle, 
                        featureFlags
                    )
                    Toast.makeText(context, "Call registered in log", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        floatingActionButton = {
            if (isReady && phoneNumber.isNotBlank()) {
                StartCallFAB(enabled = !isBusy) {
                    val customTime = getSelectedTimestamp()
                    val duration = if (durationSeconds.isBlank() || durationSeconds == "0") 0L else durationSeconds.toLongOrNull() ?: 0L
                    scope.launch {
                        if (callDirection == CallLog.Calls.OUTGOING_TYPE) {
                            telecomHelper.startOutgoingCall(phoneNumber, autoAnswerDelay.toIntOrNull() ?: 0, customTime, duration, selectedSim?.handle, featureFlags)
                        } else {
                            telecomHelper.startIncomingCall(phoneNumber, callDirection, autoAnswerDelay.toIntOrNull() ?: 0, customTime, duration, selectedSim?.handle, featureFlags)
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 16.dp)
            ) {
                SetupHeader()

                SystemStatusDashboard(systemStatus) {
                    (context as? MainActivity)?.triggerRepair()
                }

                if (isBusy && busyMessage != null) BusyBanner(busyMessage!!)

                if (!isExpanded) {
                    formContent()
                } else {
                    Spacer(Modifier.height(16.dp))
                    Text("Ready to simulate secure calls. Configure your parameters on the right.", style = MaterialTheme.typography.bodyLarge)
                }
            }

            if (isExpanded) {
                Column(
                    modifier = Modifier
                        .weight(1.5f)
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 16.dp)
                ) {
                    formContent()
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
            timeText = Calendar.getInstance().apply { 
                set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                set(Calendar.MINUTE, timePickerState.minute) 
            }.let { timeFormatter.format(it.time) },
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
