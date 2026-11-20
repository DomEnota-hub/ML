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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class Work(val track: DuplicateTrack, val appId: String?, val group: DuplicateGroup)
internal data class Outcome(val name: String, val success: Boolean, val reason: String)

/** Kept by the activity ViewModel for music, so rotation/consent cannot discard
 * a confirmed selection or replay a completed transfer. Never persisted in a Bundle. */
internal class DuplicateTrashOperation(val request:DuplicateTrashSelection,visual:Boolean=false){
    val mutex=Mutex()
    val outcomes=mutableStateMapOf<String,Outcome>()
    val queue=mutableStateOf<List<Work>>(emptyList())
    val initialized=mutableStateOf(false)
    val waiting=mutableStateOf(false)
    val pending=mutableStateOf<List<Work>>(emptyList())
    val consentResult=mutableStateOf<Int?>(null)
    val finished=mutableStateOf(false)
    val reportSaved=mutableStateOf(true)
    val revision=mutableIntStateOf(0)
    val batchLimit=mutableIntStateOf(if(visual)1 else 50)
}

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
    onAppTrash: suspend (String,String) -> Unit, onTrashed: suspend (Set<String>) -> Unit, onClose: () -> Unit, visual: Map<String,VisualFingerprint>? = null,
    retained:DuplicateTrashOperation?=null,catalogReady:Boolean=true) {
    val context = LocalContext.current
    val latestRefs by rememberUpdatedState(refs)
    val latestCurrent by rememberUpdatedState(currentId)
    val latestOnTrashed by rememberUpdatedState(onTrashed)
    val tracks = remember(request) { request.groups.flatMap { it.tracks }.associateBy { it.id } }
    val local=remember(request){DuplicateTrashOperation(request,visual!=null)}
    val operation=retained?:local
    val outcomes=operation.outcomes
    var queue by operation.queue
    var initialized by operation.initialized
    var waiting by operation.waiting
    var pending by operation.pending
    var consentResult by operation.consentResult
    var finished by operation.finished
    var revision by operation.revision
    var batchLimit by operation.batchLimit
    val privacyRevision by DuplicatePrivacyGuard.changes.collectAsState()
    val latestAppTrash by rememberUpdatedState(onAppTrash)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        if(waiting){
            consentResult = it.resultCode
            waiting = false
            revision++
        }
    }
    fun failure(w: Work, reason: String) {
        outcomes[w.track.id] = Outcome(w.track.filename,false,reason)
        AppLog.w("Trash", "Duplicate transfer not confirmed")
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
                if (fingerprint.uri != snapshot.uri && VisualDuplicateScanner.verify(context,fingerprint,ref) &&
                    latestRefs[keeper.id]?.let { VisualDuplicateScanner.matchesCatalog(fingerprint,it) } == true) { safe = true; break }
            }
            check(safe) { "Сохраняемый экземпляр не прошёл проверку" }
            val live = latestRefs[w.track.id]
            check(live != null && live.appDownloadId == w.appId && VisualDuplicateScanner.matchesCatalog(snapshot,live)) { "Каталог изменился" }
            return
        }
        val rows=w.group.tracks.mapNotNull{t->latestRefs[t.id]?.item?.copy(id=t.id)}
        MusicDuplicateScanner.verifyMember(context,w.group,w.track.id,request.selected,rows)
        val live=latestRefs[w.track.id]
        check(live!=null&&live.item.savedUri==w.track.uri&&live.appDownloadId==w.appId&&
            !live.item.hidden&&!live.item.isPrivate&&live.key!=latestCurrent&&w.track.id!=latestCurrent&&
            !com.domenota.medialoader.data.playback.AudioPlaybackService.isCurrentPublicUri(w.track.uri)){"Запись или текущий трек изменились"}
    }
    suspend fun success(w: Work) {
        outcomes[w.track.id] = Outcome(w.track.filename,true,"Перемещено")
        AppLog.i("Trash", "Duplicate transfer confirmed")
        // File movement is already confirmed; a journal failure must never trigger
        // another deletion of the same URI.
        try { latestOnTrashed(setOf(w.track.uri)) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { outcomes[w.track.id] = Outcome(w.track.filename,true,"Перемещено; журнал требует обновления") }
    }
    // Initialization and processing share one coroutine. Never restart a worker
    // merely because its own initialization flag changes.
    LaunchedEffect(operation, revision,catalogReady) {
        if(!catalogReady)return@LaunchedEffect
        operation.mutex.withLock{
        if(waiting||finished)return@withLock
        if(!initialized){
        request.failures.filterKeys{it in request.selected}.forEach { (id,why) -> outcomes[id] = Outcome(tracks[id]?.filename ?: "Файл",false,why) }
        queue = request.groups.flatMap { group ->
            group.tracks.filter { it.id in request.verified && it.id in request.selected && it.id !in outcomes }.mapNotNull { t ->
                val ref = latestRefs[t.id]
                if (ref == null || ref.item.savedUri != t.uri || ref.item.isPrivate || ref.item.hidden || ref.item.mediaType != (visual?.get(t.id)?.type ?: MediaType.AUDIO)) {
                    outcomes[t.id] = Outcome(t.filename,false,"Запись изменилась"); null
                } else Work(t,ref.appDownloadId,group)
            }
        }
        initialized = true
        }
        suspend fun finishReport(){
            request.unaccounted(outcomes.keys).forEach{id->
                outcomes[id]=Outcome(tracks[id]?.filename?:"Файл",false,"Перемещение не подтверждено. Проверьте файл и повторите.")
            }
            if(visual==null){
                val epoch=tracks.values.firstOrNull()?.privacyEpoch?:DuplicatePrivacyGuard.generation(context)
                operation.reportSaved.value=withContext(NonCancellable){
                    MusicDuplicateSessionStore.saveTrashReport(context,DuplicateTrashReport(System.currentTimeMillis(),epoch,
                        request.selected.map{id->val row=outcomes.getValue(id)
                            DuplicateTrashReportRow(id,row.name,row.success,row.reason)}))
                }
            }
            finished=true
        }
        val answer = consentResult
        if (answer != null) {
            val batch = pending
            // Even cancellation can follow a partial provider operation. Check all
            // requested URIs before deciding which ones may be retried.
            for (w in batch) {
                if(w.track.id in outcomes)continue
                withContext(NonCancellable){
                    if (confirmedSystemTrash(context, Uri.parse(w.track.uri))) success(w)
                    else failure(w, if (answer == Activity.RESULT_OK) "Android не подтвердил перемещение" else "Разрешение отменено или отклонено")
                }
            }
            pending = emptyList()
            consentResult = null
            if (answer != Activity.RESULT_OK) {
                queue.forEach { failure(it,"Операция остановлена после отмены разрешения") }
                queue = emptyList(); finishReport()
                return@withLock
            }
        }
        while (queue.isNotEmpty()) {
            queue=queue.filterNot{it.track.id in outcomes}
            if(queue.isEmpty())break
            val w = queue.first()
            if (w.appId != null) {
                try {
                    verifyWork(w)
                    withContext(NonCancellable){
                        latestAppTrash(w.appId,w.track.uri)
                        success(w)
                        queue=queue.drop(1)
                    }
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
                            queue=queue.drop(1)
                            return@withLock
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (failure: Exception) { pending = emptyList(); waiting = false; failure(w,failure.message ?: "Нет доступа к файлу") }
                    } else failure(w,e.message ?: "Нет разрешения на перенос")
                } catch (e: Exception) { failure(w,e.message ?: "Ошибка переноса в корзину") }
            } else {
                val candidate = queue.takeWhile { it.appId == null }.take(batchLimit)
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
                    queue=queue.drop(candidate.size)
                    return@withLock
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    waiting = false; pending = emptyList()
                    // Invalid batch construction can be caused by one URI. Split
                    // only this local failure, not a cancelled system permission.
                    if (e is IllegalArgumentException && valid.size > 1) {
                        batchLimit = (valid.size / 2).coerceAtLeast(1)
                        // Keep unprocessed entries in place; retry a smaller batch.
                    } else valid.forEach { failure(it,e.message ?: "Не удалось запросить системную корзину") }
                }
            }
        }
        finishReport()
        }
    }
    if (finished) {
        val ok = outcomes.values.count { it.success }
        val failed = outcomes.entries.filterNot { it.value.success }
        val warnings = outcomes.entries.filter { it.value.success && it.value.reason != "Перемещено" }
        val problems = failed + warnings
        val total = request.selected.size
        var detailsOpen by remember(operation) { mutableStateOf(false) }
        fun public(id:String) = tracks[id]?.let{
            it.privacyEpoch == privacyRevision &&
                DuplicatePrivacyGuard.accepts(context,it.privacyEpoch,id)
        } == true
        Dialog(onDismissRequest = onClose) {
            Surface(
                modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp).testTag("duplicateTrashResult"),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Rounded.LibraryMusic, contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,modifier=Modifier.size(28.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Дубликаты треков",style=MaterialTheme.typography.titleMedium)
                            Text("Результат перемещения",style=MaterialTheme.typography.bodySmall,
                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null,
                            tint=MaterialTheme.colorScheme.primary)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(Modifier.weight(1f),shape=RoundedCornerShape(14.dp),
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.60f)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("В корзине",style=MaterialTheme.typography.labelMedium,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("$ok",modifier=Modifier.testTag("duplicateTrashSuccessCount"),
                                    style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            }
                        }
                        Surface(Modifier.weight(1f),shape=RoundedCornerShape(14.dp),
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.60f)) {
                            Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text("Не перемещено",style=MaterialTheme.typography.labelMedium,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${failed.size}",modifier=Modifier.testTag("duplicateTrashFailureCount"),
                                    style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                    Column(verticalArrangement=Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Text("Обработано $total из $total",style=MaterialTheme.typography.bodySmall)
                            Text("100%",style=MaterialTheme.typography.labelMedium,
                                color=MaterialTheme.colorScheme.primary)
                        }
                        LinearProgressIndicator(
                            progress = { if(total>0) (ok.toFloat()/total).coerceIn(0f,1f) else 0f },
                            modifier=Modifier.fillMaxWidth().height(7.dp),
                        )
                    }
                    if(problems.isNotEmpty()) {
                        Surface(shape=RoundedCornerShape(14.dp),
                            color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=0.55f)) {
                            Column(Modifier.fillMaxWidth().padding(horizontal=12.dp,vertical=8.dp)) {
                                TextButton(
                                    onClick={detailsOpen=!detailsOpen},
                                    modifier=Modifier.fillMaxWidth().testTag("duplicateTrashErrorToggle"),
                                ) {
                                    Icon(Icons.Rounded.WarningAmber,contentDescription=null,
                                        tint=MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(if(detailsOpen)"Скрыть подробности" else
                                        "Подробности ошибок (${problems.size})",modifier=Modifier.weight(1f))
                                    Icon(if(detailsOpen) Icons.Rounded.KeyboardArrowUp else
                                        Icons.Rounded.KeyboardArrowDown,contentDescription=null)
                                }
                                if(detailsOpen) {
                                    Column(Modifier.fillMaxWidth().heightIn(max=220.dp)
                                        .verticalScroll(rememberScrollState())
                                        .testTag("duplicateTrashErrorList"),
                                        verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                        problems.forEach { (id,row) ->
                                            HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
                                            Text(if(public(id))row.name else "Файл",
                                                style=MaterialTheme.typography.bodyMedium,
                                                maxLines=2,overflow=TextOverflow.Ellipsis)
                                            Text(if(public(id))row.reason else "Данные недоступны",
                                                style=MaterialTheme.typography.bodySmall,
                                                color=MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if(failed.isNotEmpty()) {
                        Text("Оставшиеся файлы доступны в последнем поиске. Повтор — после проверки выбранных файлов.",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if(!operation.reportSaved.value) {
                        Text("Не удалось сохранить отчёт перемещения",
                            style=MaterialTheme.typography.bodySmall,
                            color=MaterialTheme.colorScheme.error)
                    }
                    Button(onClick=onClose,modifier=Modifier.fillMaxWidth().testTag("duplicateTrashReturn")) {
                        Text("Вернуться к дубликатам")
                    }
                }
            }
        }
    } else if (!waiting) AlertDialog(onDismissRequest = {}, title = { Text("Перемещение в корзину") },
        text = { Text("Обработано: ${outcomes.size} из ${request.selected.size}") }, confirmButton = {})
}
