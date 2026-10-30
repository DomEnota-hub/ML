package com.domenota.medialoader.ui.components

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Stable request snapshot: provider notifications cannot remove the pending result handler. */
@Composable
internal fun LibraryMediaDeleteHost(targets: List<MediaDeletionTarget>, onDismiss: () -> Unit,
    onDeleteApp: suspend (String) -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val requestKey = remember(targets) { targets.joinToString("\u0000") { it.id } }
    val phoneBatches = remember(targets) { MediaDeletionPolicy.phoneBatches(targets) }
    val appTargets = remember(targets) { targets.filterNot { it.phone } }
    val canManage = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
    val localConfirmation = MediaDeletionPolicy.needsAppConfirmation(Build.VERSION.SDK_INT, canManage, targets)
    var confirmed by rememberSaveable(requestKey) { mutableStateOf(!localConfirmation) }
    var batchIndex by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var legacyIndex by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var appIndex by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var waiting by rememberSaveable(requestKey) { mutableStateOf(false) }
    var revision by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var error by rememberSaveable(requestKey) { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        waiting = false
        if (result.resultCode != Activity.RESULT_OK) onDismiss()
        else {
            if (Build.VERSION.SDK_INT >= 30) batchIndex++
            revision++ // API29 retries the same authorized URI after RecoverableSecurityException.
        }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        waiting = false
        if (granted) revision++ else { confirmed = false; error = "Нет разрешения на удаление файлов" }
    }
    LaunchedEffect(confirmed, revision) {
        if (!confirmed || waiting) return@LaunchedEffect
        try {
            if (Build.VERSION.SDK_INT >= 30 && batchIndex < phoneBatches.size) {
                val uris = phoneBatches[batchIndex].map(Uri::parse)
                require(uris.all { it.scheme == "content" && it.authority == "media" })
                waiting = true
                consent.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender).build())
                return@LaunchedEffect
            }
            if (Build.VERSION.SDK_INT < 30) {
                val uris = phoneBatches.flatten()
                if (uris.isNotEmpty() && Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    waiting = true
                    legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    return@LaunchedEffect
                }
                while (legacyIndex < uris.size) {
                    val uri = Uri.parse(uris[legacyIndex])
                    require(uri.scheme == "content" && uri.authority == "media")
                    withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) }
                    legacyIndex++
                }
            }
            while (appIndex < appTargets.size) { onDeleteApp(appTargets[appIndex].id); appIndex++ }
            onDeleted()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                waiting = true
                consent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
            } else {
                waiting = false; confirmed = false
                error = "Не удалось завершить удаление. Уже обработанные объекты не будут удаляться повторно."
            }
        }
    }
    if (!confirmed) AlertDialog(onDismissRequest = onDismiss,
        title = { Text(deleteSelectionTitle(targets.size)) },
        text = error?.let { message -> { Text(message) } },
        confirmButton = { TextButton({ error = null; confirmed = true; revision++ }) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
    else if (!waiting) AlertDialog(onDismissRequest = {}, confirmButton = {},
        text = { Text("Удаляем выбранные объекты…") })
}
