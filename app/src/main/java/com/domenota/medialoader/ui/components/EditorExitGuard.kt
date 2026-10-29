package com.domenota.medialoader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue

/**
 * Intercepts system/app-bar back only when the current editor has local, unsaved changes.
 * A running background export is deliberately not part of [hasUnsavedChanges].
 */
@Composable
fun rememberEditorExitRequest(
    hasUnsavedChanges: Boolean,
    onExit: () -> Unit,
): () -> Unit {
    var confirmExit by remember { mutableStateOf(false) }
    val latestHasChanges by rememberUpdatedState(hasUnsavedChanges)
    val latestOnExit by rememberUpdatedState(onExit)

    val requestExit = remember {
        {
            if (latestHasChanges) confirmExit = true else latestOnExit()
        }
    }

    BackHandler(onBack = requestExit)

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text("Есть несохранённые изменения") },
            text = { Text("Выйти без сохранения?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmExit = false
                        latestOnExit()
                    },
                ) {
                    Text("Выйти")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text("Продолжить редактирование")
                }
            },
        )
    }

    return requestExit
}
