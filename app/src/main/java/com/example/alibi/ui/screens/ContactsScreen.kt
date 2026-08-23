package com.example.alibi.ui.screens

import android.provider.ContactsContract
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.alibi.util.ContactsHelper
import com.example.alibi.MainActivity

data class ContactItem(val name: String, val number: String)

@Composable
fun ContactsScreen(
    searchQuery: String = "",
    onContactClick: (String) -> Unit
) {
    val context = LocalContext.current
    val contactsHelper = remember { ContactsHelper(context) }
    val systemStatus = MainActivity.LocalSystemStatus.current
    
    val hasPermission = systemStatus.isPhonePermissionsGranted

    val contacts by if (hasPermission) {
        contactsHelper.getContactsFlow().collectAsStateWithLifecycle(initialValue = null)
    } else {
        remember { mutableStateOf(emptyList<ContactItem>()) }
    }

    val filteredContacts = remember(searchQuery, contacts) {
        val list = contacts ?: emptyList()
        if (searchQuery.isEmpty()) {
            list
        } else {
            list.filter { it.name.contains(searchQuery, ignoreCase = true) || it.number.contains(searchQuery) }
        }
    }

    if (!hasPermission) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Grant Contact Permission to view contacts")
        }
    } else if (contacts == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(filteredContacts) { contact ->
                ContactListItem(contact = contact, onClick = { onContactClick(contact.number) })
            }
        }
    }
}

@Composable
fun ContactListItem(contact: ContactItem, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onClick() },
        headlineContent = { 
            Text(
                text = contact.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
        },
        supportingContent = { 
            Text(
                text = contact.number,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        },
        trailingContent = {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
        }
    )
}
