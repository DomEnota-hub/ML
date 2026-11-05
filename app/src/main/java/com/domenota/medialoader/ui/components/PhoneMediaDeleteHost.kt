package com.domenota.medialoader.ui.components

import android.app.Activity
import android.app.RecoverableSecurityException
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class DeletionPhase { CHOICE, PERMANENT_CONFIRMATION, TRASH, PERMANENT }
private enum class DeletionConsent { NONE, PHONE_BATCH, RETRY_CURRENT }

/** Stable request snapshot: provider notifications cannot remove the pending result handler. */
@Composable
internal fun LibraryMediaDeleteHost(targets: List<MediaDeletionTarget>, onDismiss: () -> Unit,
    onDeleteApp: suspend (String) -> Unit, onDeleteAppPermanently: suspend (String) -> Unit,
    onDeleted: () -> Unit) {
    val context = LocalContext.current
    val requestKey = remember(targets) { targets.joinToString("\u0000") { it.id } }
    val phoneBatches = remember(targets) { MediaDeletionPolicy.phoneBatches(targets) }
    val phoneUris = remember(phoneBatches) { phoneBatches.flatten() }
    val appTargets = remember(targets) { targets.filterNot { it.phone } }
    var phase by rememberSaveable(requestKey) { mutableStateOf(DeletionPhase.CHOICE) }
    var batchIndex by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var phoneIndex by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var appIndex by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var waiting by rememberSaveable(requestKey) { mutableStateOf(false) }
    var consentKind by rememberSaveable(requestKey) { mutableStateOf(DeletionConsent.NONE) }
    var revision by rememberSaveable(requestKey) { mutableIntStateOf(0) }
    var error by rememberSaveable(requestKey) { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        waiting = false
        if (result.resultCode != Activity.RESULT_OK) onDismiss()
        else {
            // API30+ performs the entire phone batch itself. A recoverable grant on
            // API29, or a grant for an app record, must retry that same operation.
            if (consentKind == DeletionConsent.PHONE_BATCH) batchIndex++
            consentKind = DeletionConsent.NONE
            revision++
        }
    }
    fun requestConsent(request: IntentSenderRequest, kind: DeletionConsent) {
        consentKind = kind
        waiting = true
        consent.launch(request)
    }
    fun recoverDeletionAccess(failure: SecurityException, uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT == 29 && failure is RecoverableSecurityException) {
            requestConsent(IntentSenderRequest.Builder(failure.userAction.actionIntent.intentSender).build(),
                DeletionConsent.RETRY_CURRENT)
            return true
        }
        if (Build.VERSION.SDK_INT >= 30 && uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
            requestConsent(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(
                context.contentResolver, listOf(uri)).intentSender).build(), DeletionConsent.RETRY_CURRENT)
            return true
        }
        return false
    }
    LaunchedEffect(phase, revision) {
        if (phase != DeletionPhase.TRASH && phase != DeletionPhase.PERMANENT || waiting || error != null) return@LaunchedEffect
        val permanent = phase == DeletionPhase.PERMANENT
        try {
            if (!permanent && Build.VERSION.SDK_INT < 30 && phoneBatches.isNotEmpty()) {
                error = "На этой версии Android корзина файлов телефона недоступна. Файлы не удалены."
                return@LaunchedEffect
            }
            if (Build.VERSION.SDK_INT >= 30 && batchIndex < phoneBatches.size) {
                val uris = phoneBatches[batchIndex].map(Uri::parse)
                require(uris.all { it.scheme == "content" && it.authority == MediaStore.AUTHORITY })
                val pending = if (permanent) MediaStore.createDeleteRequest(context.contentResolver, uris)
                    else MediaStore.createTrashRequest(context.contentResolver, uris, true)
                requestConsent(IntentSenderRequest.Builder(pending.intentSender).build(), DeletionConsent.PHONE_BATCH)
                return@LaunchedEffect
            }
            if (permanent && Build.VERSION.SDK_INT < 30) {
                while (phoneIndex < phoneUris.size) {
                    val uri = Uri.parse(phoneUris[phoneIndex])
                    require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
                    try {
                        val deleted = withContext(Dispatchers.IO) { context.contentResolver.delete(uri, null, null) }
                        // A zero result is not proof that the requested file was removed.
                        check(deleted > 0) { "Файл не удалён" }
                    } catch (failure: SecurityException) {
                        if (recoverDeletionAccess(failure, uri)) return@LaunchedEffect
                        throw failure
                    }
                    phoneIndex++
                }
            }
            while (appIndex < appTargets.size) {
                val target = appTargets[appIndex]
                try {
                    if (permanent) onDeleteAppPermanently(target.id) else onDeleteApp(target.id)
                } catch (failure: SecurityException) {
                    if (permanent && target.uri != null && recoverDeletionAccess(failure, Uri.parse(target.uri)))
                        return@LaunchedEffect
                    throw failure
                }
                appIndex++
            }
            onDeleted()
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            waiting = false
            consentKind = DeletionConsent.NONE
            error = if (permanent) "Не удалось удалить все выбранные объекты. Уже удалённые объекты не будут обрабатываться повторно."
                else "Не удалось завершить перенос в корзину. Уже обработанные объекты не будут перемещаться повторно."
        }
    }
    when {
        phase == DeletionPhase.CHOICE -> AlertDialog(onDismissRequest = onDismiss,
            title = { Text(deleteSelectionTitle(targets.size)) },
            text = { Text("Выбрано объектов: ${targets.size}. Их можно переместить в корзину или удалить навсегда.") },
            confirmButton = { Column {
                TextButton({ phase = DeletionPhase.TRASH }) { Text("В корзину") }
                TextButton({ phase = DeletionPhase.PERMANENT_CONFIRMATION }) {
                    Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
                }
            } }, dismissButton = { TextButton(onDismiss) { Text("Отмена") } })
        phase == DeletionPhase.PERMANENT_CONFIRMATION -> AlertDialog(onDismissRequest = onDismiss,
            title = { Text("Удалить навсегда?") },
            text = { Text("Выбрано объектов: ${targets.size}. Файлы будут удалены с устройства без переноса в корзину. Восстановить их не получится.") },
            confirmButton = { TextButton({ phase = DeletionPhase.PERMANENT }) {
                Text("Удалить навсегда", color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton({ phase = DeletionPhase.CHOICE }) { Text("Отмена") } })
        error != null -> AlertDialog(onDismissRequest = onDismiss,
            title = { Text("Действие не завершено") }, text = { Text(error.orEmpty()) },
            confirmButton = { TextButton({ error = null; revision++ }) { Text("Повторить") } },
            dismissButton = { TextButton(onDismiss) { Text("Закрыть") } })
        !waiting -> AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Text(if (phase == DeletionPhase.PERMANENT) "Удаляем выбранные объекты навсегда…"
                else "Перемещаем выбранные объекты в корзину…")
        })
    }
}
