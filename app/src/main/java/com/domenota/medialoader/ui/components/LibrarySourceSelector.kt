package com.domenota.medialoader.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.library.LibrarySource

@Composable
internal fun LibrarySourceSelector(source: LibrarySource, onSource: (LibrarySource) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.semantics { contentDescription = "Источник файлов" }.clickable { expanded = true }.heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Icon(if (source == LibrarySource.APP) Icons.Rounded.PhotoLibrary else Icons.Rounded.PhoneAndroid,
                null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            Text(if (source == LibrarySource.APP) "Файлы приложения" else "Все файлы телефона",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Icon(Icons.Rounded.ExpandMore, null, Modifier.size(16.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, { expanded = false }) {
            LibrarySource.entries.forEach { entry ->
                DropdownMenuItem(text = { Text(if (entry == LibrarySource.APP) "Файлы приложения" else "Все файлы телефона") },
                    onClick = { expanded = false; if (source != entry) onSource(entry) },
                    leadingIcon = { Icon(if (entry == LibrarySource.APP) Icons.Rounded.PhotoLibrary else Icons.Rounded.PhoneAndroid, null) },
                    trailingIcon = { if (source == entry) Icon(Icons.Rounded.Check, null) })
            }
        }
    }
}
