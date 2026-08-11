package com.example.alibi.ui.screens

import android.provider.CallLog
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.util.CallLogHelper
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

enum class PhoneSubTab { RECENTS, CONTACTS }

/**
 * Main Dialer interface. Manages sub-tabs, search, and the interactive dial pad.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(initialNumber: String? = null) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val telecomHelper = remember { TelecomHelper(context) }
    val callLogHelper = remember { CallLogHelper.getInstance(context) }
    
    val isBusy by CallStateManager.isBusy.collectAsStateWithLifecycle()
    val busyMessage by CallStateManager.busyMessage.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(PhoneSubTab.RECENTS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var phoneNumber by rememberSaveable { mutableStateOf(initialNumber ?: "") }
    
    // --- Reactive Data ---
    val hasCallLogPermission by remember { 
        mutableStateOf(ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CALL_LOG) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    val recentCalls by if (hasCallLogPermission) {
        callLogHelper.getRecentCallsFlow(500).collectAsStateWithLifecycle(null)
    } else {
        remember { mutableStateOf(emptyList()) }
    }
    
    val simAccounts = remember { mutableStateListOf<TelecomHelper.SimAccount>() }
    LaunchedEffect(Unit) {
        simAccounts.clear()
        simAccounts.addAll(telecomHelper.getCallCapableSims())
    }
    
    // --- UI State ---
    val listState = rememberLazyListState()
    var dialPadVisible by remember { mutableStateOf(false) }
    
    val isLoading = recentCalls == null
    val displayCalls = recentCalls ?: emptyList()
    var selectedSim by remember { mutableStateOf<TelecomHelper.SimAccount?>(null) }

    LaunchedEffect(simAccounts.size) {
        if (selectedSim == null && simAccounts.isNotEmpty()) {
            selectedSim = simAccounts.find { it.handle.id == telecomHelper.getPreferredSimId() } ?: simAccounts.firstOrNull()
        }
    }

    // --- Logic Hooks ---
    BackHandler(enabled = dialPadVisible && phoneNumber.isEmpty()) { dialPadVisible = false }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 10)) {
            dialPadVisible = false
        }
    }

    // Removed auto-restore effect to keep dial pad hidden by default as requested.

    // --- Layout ---
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (isBusy && busyMessage != null) {
            BusyBanner(message = busyMessage!!)
        }

        TabSelector(
            selectedTab = selectedTab,
            onTabSelected = { selectedTab = it }
        )

        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = if (selectedTab == PhoneSubTab.CONTACTS) "Search contacts" else "Search calls"
        )

        Box(modifier = Modifier.weight(1f)) {
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                when (selectedTab) {
                    PhoneSubTab.RECENTS -> {
                        RecentCallsList(
                            state = listState,
                            calls = displayCalls.filter { it.number.contains(searchQuery) || (it.name?.contains(searchQuery, true) ?: false) },
                            sims = simAccounts,
                            onCallClick = { 
                                phoneNumber = it
                                dialPadVisible = true
                            }
                        )
                    }
                    PhoneSubTab.CONTACTS -> {
                        ContactsScreen(
                            searchQuery = searchQuery,
                            onContactClick = { 
                                phoneNumber = it
                                selectedTab = PhoneSubTab.RECENTS
                                dialPadVisible = true
                            }
                        )
                    }
                }
            }

            if (!dialPadVisible && selectedTab == PhoneSubTab.RECENTS && !isLoading) {
                FloatingActionButton(
                    onClick = { dialPadVisible = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape
                ) { Icon(Icons.Rounded.Dialpad, contentDescription = null) }
            }
        }

        AnimatedVisibility(
            visible = dialPadVisible && selectedTab == PhoneSubTab.RECENTS,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            DialPad(
                phoneNumber = phoneNumber,
                selectedSim = selectedSim,
                availableSims = simAccounts,
                onDigitClick = { phoneNumber += it },
                onBackspace = { if (phoneNumber.isNotEmpty()) phoneNumber = phoneNumber.dropLast(1) },
                onSimSelected = { 
                    selectedSim = it
                    telecomHelper.setPreferredSimId(it.handle.id)
                },
                onCallClick = { 
                    if (phoneNumber.isNotEmpty() && !isBusy) {
                        scope.launch {
                            telecomHelper.placeRealCall(phoneNumber, selectedSim?.handle)
                        }
                    }
                }
            )
        }
    }
}

// --- Sub-Components ---

@Composable
private fun BusyBanner(message: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(8.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun TabSelector(selectedTab: PhoneSubTab, onTabSelected: (PhoneSubTab) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TabItem(icon = Icons.Rounded.Call, isSelected = selectedTab == PhoneSubTab.RECENTS, onClick = { onTabSelected(PhoneSubTab.RECENTS) })
        Spacer(Modifier.width(48.dp))
        TabItem(icon = Icons.Rounded.Person, isSelected = selectedTab == PhoneSubTab.CONTACTS, onClick = { onTabSelected(PhoneSubTab.CONTACTS) })
    }
}

@Composable
private fun TabItem(icon: ImageVector, isSelected: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = if (isSelected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape) else Modifier
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
                )
            }
        }
    }
}

@Composable
fun RecentCallsList(state: LazyListState, calls: List<CallLogHelper.CallLogItem>, sims: List<TelecomHelper.SimAccount>, onCallClick: (String) -> Unit) {
    LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(calls, key = { it.id }) { call ->
            val sim = sims.find { it.handle.id == call.phoneAccountId && it.handle.componentName.flattenToString() == call.phoneAccountComponent } 
                ?: sims.find { it.handle.id == call.phoneAccountId }
            RecentCallListItem(call = call, simLabel = sim?.label ?: "Unknown", onClick = { onCallClick(call.number) })
        }
    }
}

@Composable
private fun RecentCallListItem(call: CallLogHelper.CallLogItem, simLabel: String, onClick: () -> Unit) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault()) }
    val isMissed = call.type == CallLog.Calls.MISSED_TYPE || call.type == CallLog.Calls.REJECTED_TYPE
    
    val directionText = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
        CallLog.Calls.REJECTED_TYPE -> "Rejected"
        CallLog.Calls.BLOCKED_TYPE -> "Blocked"
        else -> "Call"
    }
    
    val icon = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> Icons.AutoMirrored.Rounded.CallReceived
        CallLog.Calls.OUTGOING_TYPE -> Icons.AutoMirrored.Rounded.CallMade
        else -> Icons.AutoMirrored.Rounded.CallMissed
    }
    
    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { 
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = call.name ?: call.number, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                if ((call.features and CallLog.Calls.FEATURES_HD_CALL) != 0) FeatureBadge(Icons.Rounded.HighQuality)
                if ((call.features and CallLog.Calls.FEATURES_WIFI) != 0) FeatureBadge(Icons.Rounded.Wifi)
                if ((call.features and CallLog.Calls.FEATURES_VIDEO) != 0) FeatureBadge(Icons.Rounded.Videocam)
            }
        },
        supportingContent = { 
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (isMissed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(4.dp))
                    Text(text = "$directionText: $simLabel", style = MaterialTheme.typography.bodySmall)
                }
                Text(text = "Duration: ${formatDuration(call.duration)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f))
                Text(text = formatter.format(Date(call.date)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
            }
        },
        leadingContent = {
            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant) }
    )
}

@Composable
private fun FeatureBadge(icon: ImageVector) {
    Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.width(4.dp))
}

private fun formatDuration(seconds: Long): String {
    return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialPad(phoneNumber: String, selectedSim: TelecomHelper.SimAccount?, availableSims: List<TelecomHelper.SimAccount>, onDigitClick: (String) -> Unit, onBackspace: () -> Unit, onSimSelected: (TelecomHelper.SimAccount) -> Unit, onCallClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        tonalElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            NumberDisplay(phoneNumber = phoneNumber, onBackspace = onBackspace)

            val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("*", "0", "#"))
            keys.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { key -> DialKey(text = key, onClick = { onDigitClick(key) }) }
                }
                Spacer(Modifier.height(8.dp))
            }

            DialPadActions(
                availableSims = availableSims,
                selectedSim = selectedSim,
                onSimSelected = onSimSelected,
                onCallClick = onCallClick
            )
        }
    }
}

@Composable
private fun NumberDisplay(phoneNumber: String, onBackspace: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth().height(52.dp), contentAlignment = Alignment.Center) {
        Text(text = phoneNumber, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
        if (phoneNumber.isNotEmpty()) {
            IconButton(onClick = onBackspace, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun DialPadActions(availableSims: List<TelecomHelper.SimAccount>, selectedSim: TelecomHelper.SimAccount?, onSimSelected: (TelecomHelper.SimAccount) -> Unit, onCallClick: () -> Unit) {
    var showSimMenu by remember { mutableStateOf(false) }

    Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (availableSims.size > 1) {
                SimSelector(selectedSim = selectedSim, onClick = { showSimMenu = true })
                
                SimSelectionMenu(
                    expanded = showSimMenu, 
                    sims = availableSims, 
                    onDismiss = { showSimMenu = false }, 
                    onSimSelected = onSimSelected
                )
            } else Spacer(Modifier.size(40.dp))
        }

        Button(
            onClick = onCallClick,
            modifier = Modifier.size(56.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
            contentPadding = PaddingValues(0.dp)
        ) { Icon(Icons.Rounded.Call, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp)) }

        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun SimSelector(selectedSim: TelecomHelper.SimAccount?, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.SimCard, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(text = selectedSim?.label?.take(8) ?: "SIM", style = MaterialTheme.typography.labelSmall)
            }
            selectedSim?.address?.let { Text(text = it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 7.sp), color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)) }
        }
    }
}

@Composable
private fun SimSelectionMenu(expanded: Boolean, sims: List<TelecomHelper.SimAccount>, onDismiss: () -> Unit, onSimSelected: (TelecomHelper.SimAccount) -> Unit) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        sims.forEach { sim ->
            DropdownMenuItem(
                text = { Column { Text(sim.label); sim.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) } } },
                onClick = { onSimSelected(sim); onDismiss() }
            )
        }
    }
}

@Composable
fun DialKey(text: String, onClick: () -> Unit) {
    Surface(onClick = onClick, modifier = Modifier.size(68.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)) {
        Box(contentAlignment = Alignment.Center) {
            Text(text = text, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Normal)
        }
    }
}
