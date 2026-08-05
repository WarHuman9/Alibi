package com.example.alibi.ui.screens

import androidx.activity.compose.BackHandler
import android.provider.CallLog
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.CallMade
import androidx.compose.material.icons.automirrored.rounded.CallMissed
import androidx.compose.material.icons.automirrored.rounded.CallReceived
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.telecom.CallStateManager
import com.example.alibi.telecom.TelecomHelper
import com.example.alibi.util.CallLogHelper
import java.text.SimpleDateFormat
import java.util.*

enum class PhoneSubTab {
    RECENTS, CONTACTS
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialerScreen(
    initialNumber: String? = null
) {
    val context = LocalContext.current
    val telecomHelper = remember { TelecomHelper(context) }
    val callLogHelper = remember { CallLogHelper.getInstance(context) }
    
    val isBusy by CallStateManager.isBusy.collectAsStateWithLifecycle()
    val busyMessage by CallStateManager.busyMessage.collectAsStateWithLifecycle()

    var selectedTab by rememberSaveable { mutableStateOf(PhoneSubTab.RECENTS) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var phoneNumber by rememberSaveable { mutableStateOf(initialNumber ?: "") }
    
    val recentCalls = remember { mutableStateListOf<CallLogHelper.CallLogItem>() }
    val simAccounts = remember { telecomHelper.getCallCapableSims() }
    
    val listState = rememberLazyListState()
    var dialPadVisible by remember { mutableStateOf(true) }

    BackHandler(enabled = dialPadVisible && phoneNumber.isEmpty()) {
        dialPadVisible = false
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            if (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 10) {
                dialPadVisible = false
            }
        }
    }

    // Automatically restore dial pad when scrolled to absolute top
    val isAtTop by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }
    
    LaunchedEffect(isAtTop) {
        if (isAtTop) dialPadVisible = true
    }

    // Persistent SIM selection
    var selectedSim by remember { 
        mutableStateOf(
            simAccounts.find { it.handle.id == telecomHelper.getPreferredSimId() } 
            ?: simAccounts.firstOrNull()
        )
    }

    LaunchedEffect(Unit) {
        recentCalls.clear()
        recentCalls.addAll(callLogHelper.getRecentCalls(20))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Sub-Tab Bar (Simplified)
        if (isBusy && busyMessage != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = busyMessage!!,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(8.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SubTabItem(
                icon = Icons.Rounded.Call,
                isSelected = selectedTab == PhoneSubTab.RECENTS,
                onClick = { selectedTab = PhoneSubTab.RECENTS }
            )
            Spacer(Modifier.width(48.dp))
            SubTabItem(
                icon = Icons.Rounded.Person,
                isSelected = selectedTab == PhoneSubTab.CONTACTS,
                onClick = { selectedTab = PhoneSubTab.CONTACTS }
            )
        }

        // Search Bar
        SearchBar(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = if (selectedTab == PhoneSubTab.CONTACTS) "Search contacts" else "Search calls"
        )

        Box(modifier = Modifier.weight(1f)) {
            when (selectedTab) {
                PhoneSubTab.RECENTS -> {
                    RecentCallsList(
                        state = listState,
                        calls = recentCalls.filter { 
                            it.number.contains(searchQuery) || (it.name?.contains(searchQuery, true) ?: false) 
                        },
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

            // Restore Dial Pad FAB
            if (!dialPadVisible && selectedTab == PhoneSubTab.RECENTS) {
                FloatingActionButton(
                    onClick = { dialPadVisible = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = CircleShape
                ) {
                    Icon(Icons.Rounded.Dialpad, contentDescription = "Show Dialer")
                }
            }
        }

        // Full-Width Dial Pad with Animation
        androidx.compose.animation.AnimatedVisibility(
            visible = dialPadVisible && selectedTab == PhoneSubTab.RECENTS,
            enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut()
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
                        telecomHelper.placeRealCall(phoneNumber, selectedSim?.handle)
                    }
                }
            )
        }
    }
}

@Composable
fun SubTabItem(icon: androidx.compose.ui.graphics.vector.ImageVector, isSelected: Boolean, onClick: () -> Unit) {
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
fun SearchBar(query: String, onQueryChange: (String) -> Unit, placeholder: String) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
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
fun RecentCallsList(
    state: androidx.compose.foundation.lazy.LazyListState,
    calls: List<CallLogHelper.CallLogItem>,
    sims: List<TelecomHelper.SimAccount>,
    onCallClick: (String) -> Unit
) {
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(calls) { call ->
            RecentCallListItem(
                call = call, 
                simLabel = sims.find { it.handle.id == call.phoneAccountId }?.label ?: "Unknown",
                onClick = { onCallClick(call.number) }
            )
        }
    }
}

@Composable
fun RecentCallListItem(
    call: CallLogHelper.CallLogItem, 
    simLabel: String,
    onClick: () -> Unit
) {
    val formatter = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val directionText = when (call.type) {
        CallLog.Calls.INCOMING_TYPE -> "Incoming"
        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
        CallLog.Calls.MISSED_TYPE -> "Missed"
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
                Text(
                    text = call.name ?: call.number,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                // Feature Badges
                if ((call.features and CallLog.Calls.FEATURES_HD_CALL) != 0) {
                    Icon(Icons.Rounded.HighQuality, contentDescription = "HD", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                if ((call.features and CallLog.Calls.FEATURES_WIFI) != 0) {
                    Icon(Icons.Rounded.Wifi, contentDescription = "WiFi", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                }
                if ((call.features and CallLog.Calls.FEATURES_VIDEO) != 0) {
                    Icon(Icons.Rounded.Videocam, contentDescription = "Video", modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        supportingContent = { 
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (call.type == CallLog.Calls.MISSED_TYPE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "$directionText: $simLabel",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                val durationText = if (call.duration >= 60) {
                    "${call.duration / 60}m ${call.duration % 60}s"
                } else {
                    "${call.duration}s"
                }
                Text(
                    text = "Duration: $durationText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Text(
                    text = formatter.format(Date(call.date)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        },
        trailingContent = {
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outlineVariant)
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialPad(
    phoneNumber: String,
    selectedSim: TelecomHelper.SimAccount?,
    availableSims: List<TelecomHelper.SimAccount>,
    onDigitClick: (String) -> Unit,
    onBackspace: () -> Unit,
    onSimSelected: (TelecomHelper.SimAccount) -> Unit,
    onCallClick: () -> Unit
) {
    var showSimMenu by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Display Number (Fixed Backspace at right)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = phoneNumber,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                if (phoneNumber.isNotEmpty()) {
                    IconButton(
                        onClick = onBackspace,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.Backspace, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 3x4 Grid (Shrunk but with larger keys)
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("*", "0", "#")
            )

            keys.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    row.forEach { key ->
                        DialKey(text = key, onClick = { onDigitClick(key) })
                    }
                }
                Spacer(Modifier.height(8.dp)) // Added space to prevent overlap
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // SIM Switch (Rectangular Box)
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if (availableSims.size > 1) {
                        Surface(
                            onClick = { showSimMenu = true },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Rounded.SimCard, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(
                                        text = selectedSim?.label?.take(8) ?: "SIM",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                selectedSim?.address?.let {
                                    Text(
                                        text = it,
                                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 7.sp),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                    )
                                }
                            }
                        }
                        DropdownMenu(
                            expanded = showSimMenu,
                            onDismissRequest = { showSimMenu = false }
                        ) {
                            availableSims.forEach { sim ->
                                DropdownMenuItem(
                                    text = { 
                                        Column {
                                            Text(sim.label)
                                            sim.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                        }
                                    },
                                    onClick = {
                                        onSimSelected(sim)
                                        showSimMenu = false
                                    }
                                )
                            }
                        }
                    } else {
                        Spacer(Modifier.size(40.dp))
                    }
                }

                // Green Call Button (Slightly smaller)
                Button(
                    onClick = onCallClick,
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Icon(Icons.Rounded.Call, contentDescription = "Call", tint = Color.White, modifier = Modifier.size(28.dp))
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun DialKey(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(68.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Normal
            )
        }
    }
}
