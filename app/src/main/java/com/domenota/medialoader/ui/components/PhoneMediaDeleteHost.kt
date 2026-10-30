package com.domenota.medialoader.ui.components

import android.Manifest
import android.app.Activity
import android.app.RecoverableSecurityException
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
import com.domenota.medialoader.core.database.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Phone deletion is separate from app trash and uses exactly one confirmation on Android 11+: app or system. */
@Composable
internal fun PhoneMediaDeleteHost(item: DownloadEntity, onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmed by rememberSaveable(item.id) { mutableStateOf(false) }
    var error by remember(item.id) { mutableStateOf<String?>(null) }
    val uri = item.savedUri?.let(Uri::parse)
    suspend fun remove(): Boolean {
        require(item.id.startsWith("phone:") && uri != null && uri.scheme == "content")
        return withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) > 0 }
    }
    fun fail() { confirmed = false; error = "Не удалось удалить файл. Проверьте разрешения и повторите." }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) onDismiss()
        else if (Build.VERSION.SDK_INT >= 30) onDeleted()
        else scope.launch {
            try { if (remove()) onDeleted() else fail() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail() }
        }
    }
    val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) fail() else scope.launch {
            try { if (remove()) onDeleted() else fail() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { fail() }
        }
    }
    fun beginDelete() {
        confirmed = true
        scope.launch {
            try {
                require(uri != null && item.id.startsWith("phone:"))
                when {
                    Build.VERSION.SDK_INT >= 30 -> consent.launch(IntentSenderRequest.Builder(
                        MediaStore.createDeleteRequest(context.contentResolver, listOf(uri)).intentSender).build())
                    Build.VERSION.SDK_INT < 29 -> legacy.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    else -> if (remove()) onDeleted() else fail()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (Build.VERSION.SDK_INT == 29 && e is RecoverableSecurityException) {
                    consent.launch(IntentSenderRequest.Builder(e.userAction.actionIntent.intentSender).build())
                } else fail()
            }
        }
    }
    // MediaStore's own consent is the confirmation when special media access is absent.
    // With MANAGE_MEDIA the request executes without a system dialog, so confirm here.
    val systemConfirmation = Build.VERSION.SDK_INT >= 30 &&
        (Build.VERSION.SDK_INT < 31 || !MediaStore.canManageMedia(context))
    LaunchedEffect(item.id) { if (systemConfirmation && !confirmed) beginDelete() }
    if ((!systemConfirmation || error != null) && !confirmed) AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Удалить выбранный объект?") },
        text = error?.let { message -> { Text(message) } },
        confirmButton = { TextButton(onClick = { error = null; beginDelete() }) {
            Text("Удалить", color = MaterialTheme.colorScheme.error)
        } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } })
}
