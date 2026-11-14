package com.domenota.medialoader.ui.components

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class Work(val track: DuplicateTrack, val appId: String?, val group: DuplicateGroup)
private data class Outcome(val name: String, val success: Boolean, val reason: String)

/** Read the actual provider state; RESULT_OK alone is not proof that every URI was trashed. */
internal suspend fun confirmedSystemTrash(context: Context, uri: Uri): Boolean = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < 30 || uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@withContext false
    val args = Bundle().apply { putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE) }
    runCatching {
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)?.use {
            it.moveToFirst() && it.getInt(0) == 1
        } == true
    }.getOrDefault(false)
}

/** Duplicate-only recoverable cleanup. No permanent deletion request or automatic permission bypass. */
@Composable
internal fun DuplicateTrashHost(request: DuplicateTrashSelection, refs: Map<String,LibraryMediaRef>, currentId: String?,
    onAppTrash: suspend (String,String) -> Unit, onTrashed: suspend (Set<String>) -> Unit, onClose: () -> Unit, visual: Map<String,VisualFingerprint>? = null) {
    val context = LocalContext.current
    val latestRefs by rememberUpdatedState(refs)
    val latestCurrent by rememberUpdatedState(currentId)
    val latestOnTrashed by rememberUpdatedState(onTrashed)
    val tracks = remember(request) { request.groups.flatMap { it.tracks }.associateBy { it.id } }
    val outcomes = remember(request) { mutableStateMapOf<String,Outcome>() }
    var queue by remember(request) { mutableStateOf<List<Work>>(emptyList()) }
    var initialized by remember(request) { mutableStateOf(false) }
    var waiting by remember(request) { mutableStateOf(false) }
    var pending by remember(request) { mutableStateOf<List<Work>>(emptyList()) }
    var consentResult by remember(request) { mutableStateOf<Int?>(null) }
    var finished by remember(request) { mutableStateOf(false) }
    var revision by remember(request) { mutableIntStateOf(0) }
    var batchLimit by remember(request) { mutableIntStateOf(50) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        consentResult = it.resultCode
        waiting = false
        revision++
    }
    fun failure(w: Work, reason: String) {
        outcomes[w.track.id] = Outcome(w.track.filename,false,reason)
        AppLog.w("Trash", "Duplicate transfer not confirmed · id=${w.track.id} · $reason")
    }
    fun groupStillSafe(w: Work): Boolean {
        // Only the explicitly unselected members can serve as keepers. Never count an
        // already processed or failed selected track as the last surviving copy.
        return w.group.tracks.any { t ->
            if (t.id in request.selected) false else {
                val row = latestRefs[t.id]?.item
                row != null && row.savedUri == t.uri && !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED
            }
        }
    }
    suspend fun verifyWork(w: Work) {
        check(groupStillSafe(w)) { "Сохраняемый экземпляр недоступен" }
        val current = latestRefs[w.track.id]
        check(current != null && current.key != latestCurrent && w.track.id != latestCurrent &&
            current.item.savedUri == w.track.uri && current.appDownloadId == w.appId &&
            !current.item.hidden && !current.item.isPrivate && current.item.mediaType == (visual?.get(w.track.id)?.type ?: MediaType.AUDIO)) { "Запись или текущий трек изменились" }
        if (visual != null) {
            val snapshot = requireNotNull(visual[w.track.id])
            check(VisualDuplicateScanner.verify(context,snapshot,requireNotNull(current))) { "Файл изменился" }
            var safe = false
            for (keeper in w.group.tracks.filter { it.id !in request.selected }) {
                val fingerprint = visual[keeper.id] ?: continue
                val ref = latestRefs[keeper.id] ?: continue
                if (VisualDuplicateScanner.verify(context,fingerprint,ref)) { safe = true; break }
            }
            check(safe) { "Сохраняемый экземпляр не прошёл проверку" }
            return
        }
        val keepers = w.group.tracks.filter { it.id !in request.selected && latestRefs[it.id]?.item?.savedUri == it.uri }
        val checkGroup = w.group.copy(tracks = listOf(w.track) + keepers)
        val rows = checkGroup.tracks.mapNotNull { latestRefs[it.id]?.item?.copy(id = it.id) }
        MusicDuplicateScanner.verify(context,listOf(checkGroup),setOf(w.track.id),rows)
    }
    suspend fun success(w: Work) {
        outcomes[w.track.id] = Outcome(w.track.filename,true,"Перемещено")
        AppLog.i("Trash", "Duplicate transfer confirmed · id=${w.track.id}")
        // File movement is already confirmed; a journal failure must never trigger
        // another deletion of the same URI.
        try { latestOnTrashed(setOf(w.track.uri)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { outcomes[w.track.id] = Outcome(w.track.filename,true,"Перемещено; журнал требует обновления") }
    }
    LaunchedEffect(request) {
        request.failures.forEach { (id,why) -> outcomes[id] = Outcome(tracks[id]?.filename ?: id,false,why) }
        queue = request.groups.flatMap { group ->
            group.tracks.filter { it.id in request.verified }.mapNotNull { t ->
                val ref = latestRefs[t.id]
                if (ref == null || ref.item.savedUri != t.uri || ref.item.isPrivate || ref.item.hidden || ref.item.mediaType != (visual?.get(t.id)?.type ?: MediaType.AUDIO)) {
                    outcomes[t.id] = Outcome(t.filename,false,"Запись изменилась"); null
                } else Work(t,ref.appDownloadId,group)
            }
        }
        initialized = true
    }
    LaunchedEffect(initialized, revision) {
        if (!initialized || waiting || finished) return@LaunchedEffect
        val answer = consentResult
        if (answer != null) {
            val batch = pending
            // Even cancellation can follow a partial provider operation. Check all
            // requested URIs before deciding which ones may be retried.
            for (w in batch) {
                if (confirmedSystemTrash(context, Uri.parse(w.track.uri))) success(w)
                else failure(w, if (answer == Activity.RESULT_OK) "Android не подтвердил перемещение" else "Разрешение отменено или отклонено")
            }
            pending = emptyList()
            consentResult = null
            if (answer != Activity.RESULT_OK) {
                queue.forEach { failure(it,"Операция остановлена после отмены разрешения") }
                queue = emptyList(); finished = true
                return@LaunchedEffect
            }
        }
        while (queue.isNotEmpty()) {
            val w = queue.first()
            if (w.appId != null) {
                queue = queue.drop(1)
                try {
                    verifyWork(w)
                    onAppTrash(w.appId,w.track.uri)
                    success(w)
                } catch (e: CancellationException) { throw e }
                catch (e: SecurityException) {
                    // A history alias can refer to a foreign MediaStore file.
                    // The private copy operation rolled back; use Android's
                    // recoverable trash, never createDeleteRequest.
                    val uri = Uri.parse(w.track.uri)
                    if (Build.VERSION.SDK_INT >= 30 && uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) {
                        try {
                            verifyWork(w)
                            pending = listOf(w)
                            val intent = MediaStore.createTrashRequest(context.contentResolver,listOf(uri),true)
                            waiting = true
                            launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                            return@LaunchedEffect
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { pending = emptyList(); waiting = false; failure(w,failure.message ?: "Нет доступа к файлу") }
                    } else failure(w,e.message ?: "Нет разрешения на перенос")
                } catch (e: Exception) { failure(w,e.message ?: "Ошибка переноса в корзину") }
            } else {
                val candidate = queue.takeWhile { it.appId == null }.take(batchLimit)
                queue = queue.drop(candidate.size)
                if (Build.VERSION.SDK_INT < 30) {
                    candidate.forEach { failure(it, "Системная корзина недоступна") }
                    continue
                }
                val valid = mutableListOf<Work>()
                for (item in candidate) {
                    try {
                        val uri = Uri.parse(item.track.uri)
                        check(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY) { "URI не поддерживает системную корзину" }
                        verifyWork(item); valid += item
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failure(item,e.message ?: "Проверка файла не прошла") }
                }
                if (valid.isEmpty()) continue
                try {
                    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        MediaStore.createTrashRequest(context.contentResolver,valid.map { Uri.parse(it.track.uri) },true)
                    } else error("Системная корзина недоступна")
                    pending = valid.toList(); waiting = true
                    launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build())
                    return@LaunchedEffect
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    waiting = false; pending = emptyList()
                    // Invalid batch construction can be caused by one URI. Split
                    // only this local failure, not a cancelled system permission.
                    if (e is IllegalArgumentException && valid.size > 1) {
                        batchLimit = (valid.size / 2).coerceAtLeast(1)
                        queue = valid + queue
                    } else valid.forEach { failure(it,e.message ?: "Не удалось запросить системную корзину") }
                }
            }
        }
        finished = true
    }
    if (finished) {
        val ok = outcomes.values.count { it.success }
        val failed = outcomes.values.filterNot { it.success }
        AlertDialog(onDismissRequest = onClose, title = { Text("Результат перемещения") }, text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Перемещено в корзину: $ok")
                Text("Не перемещено: ${failed.size}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                failed.forEach { row ->
                    HorizontalDivider()
                    Text(row.name, style = MaterialTheme.typography.bodyMedium)
                    Text(row.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (failed.isNotEmpty()) Text("Оставшиеся доступны в последнем поиске. Повтор — после проверки выбранных файлов.", style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(onClose) { Text("Вернуться к дубликатам") } })
    } else if (!waiting) AlertDialog(onDismissRequest = {}, title = { Text("Перемещение в корзину") },
        text = { Text("Обработано: ${outcomes.size} из ${request.selected.size}") }, confirmButton = {})
}
