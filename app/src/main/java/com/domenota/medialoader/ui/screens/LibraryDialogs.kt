package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun LibraryStatus(loading: Boolean, message: String?, error: String?, empty: Boolean, emptyText: String) {
    // Library indexing never reports its progress in the visible media interface.
    message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!loading && empty && message == null) Text(emptyText, Modifier.padding(vertical = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun LibraryNameDialog(title: String, initial: String, onDismiss: () -> Unit, onSave: suspend (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.width(280.dp).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Название") }, isError = error != null, enabled = !busy)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onDismiss, enabled = !busy) { Text("Отмена") }
                    TextButton(enabled = name.isNotBlank() && !busy, onClick = {
                        busy = true
                        scope.launch {
                            try { onSave(name.trim()) } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e.message ?: "Не удалось сохранить" }
                            finally { busy = false }
                        }
                    }) { Text("Сохранить") }
                }
            }
        }
    }
}
