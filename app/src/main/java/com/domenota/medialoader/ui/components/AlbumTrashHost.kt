package com.domenota.medialoader.ui.components

import android.app.Activity
import android.net.Uri
import android.os.Build
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
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal data class AlbumTrashRequest(val folderId: String, val rows: List<LibraryMediaRef>, val memberIds: Set<String>)

internal fun albumTrashMembershipSafe(request: AlbumTrashRequest, members: Set<String>, ref: LibraryMediaRef): Boolean {
    val aliases = ref.aliases + ref.key + listOfNotNull(ref.appDownloadId)
    return request.memberIds.containsAll(members) && aliases.any { it in members }
}

/** Only recoverable moves, verified per URI. The virtual album survives any partial failure. */
@Composable
internal fun AlbumTrashHost(request: AlbumTrashRequest, refs: Map<String,LibraryMediaRef>,
    currentMembers: () -> Set<String>, onAppTrash: suspend (String,String) -> Unit,
    onTrashed: suspend (LibraryMediaRef) -> Unit, onRemoveAlbum: suspend () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val liveRefs by rememberUpdatedState(refs)
    val members by rememberUpdatedState(currentMembers)
    val removeAlbum by rememberUpdatedState(onRemoveAlbum)
    val detachMember by rememberUpdatedState(onTrashed)
    var index by remember(request) { mutableIntStateOf(0) }
    var waiting by remember(request) { mutableStateOf(false) }
    var answer by remember(request) { mutableStateOf<Int?>(null) }
    var pending by remember(request) { mutableStateOf<List<LibraryMediaRef>>(emptyList()) }
    var revision by remember(request) { mutableIntStateOf(0) }
    var finished by remember(request) { mutableStateOf(false) }
    var removed by remember(request) { mutableStateOf(false) }
    var albumError by remember(request) { mutableStateOf<String?>(null) }
    val successful = remember(request) { mutableStateListOf<String>() }
    val failures = remember(request) { mutableStateMapOf<String,String>() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        answer = it.resultCode; waiting = false; revision++
    }
    suspend fun verify(ref: LibraryMediaRef) {
        val live = requireNotNull(liveRefs[ref.key]) { "Файл недоступен" }
        check(albumTrashMembershipSafe(request,members(),live)) { "Состав альбома изменился" }
        check(live.item.savedUri == ref.item.savedUri && live.item.sizeBytes == ref.item.sizeBytes &&
            live.item.createdAtEpochMillis == ref.item.createdAtEpochMillis &&
            live.appDownloadId == ref.appDownloadId && !live.item.hidden && !live.item.isPrivate &&
            live.item.state == DownloadState.COMPLETED && live.item.mediaType in setOf(MediaType.PHOTO,MediaType.VIDEO)) { "Файл изменился" }
        val uri = Uri.parse(requireNotNull(ref.item.savedUri))
        withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openFileDescriptor(uri,"r") ?: error("Файл недоступен")
            descriptor.use { fd ->
                val expected = ref.item.sizeBytes
                check(expected == null || expected <= 0L || fd.statSize < 0L || expected == fd.statSize) { "Размер файла изменился" }
            }
        }
    }
    fun requestSystemTrash(rows: List<LibraryMediaRef>) {
        val uris = rows.map { Uri.parse(requireNotNull(it.item.savedUri)) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && uris.all { it.scheme == "content" && it.authority == MediaStore.AUTHORITY }) {
            val intent = MediaStore.createTrashRequest(context.contentResolver,uris,true)
            pending = rows; waiting = true
            try { launcher.launch(IntentSenderRequest.Builder(intent.intentSender).build()) }
            catch (e: Exception) { waiting = false; pending = emptyList(); throw e }
        } else error("Системная корзина недоступна для файла")
    }
    suspend fun success(ref: LibraryMediaRef) {
        successful += ref.key
        try { detachMember(ref) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { failures[ref.key] = "Файл в корзине; связь с альбомом не обновлена: ${e.message ?: "ошибка каталога"}" }
    }
    LaunchedEffect(request, revision) {
        if (waiting || finished) return@LaunchedEffect
        val result = answer
        if (result != null) {
            for (ref in pending) {
                if (confirmedSystemTrash(context,Uri.parse(requireNotNull(ref.item.savedUri)))) success(ref)
                else failures[ref.key] = if (result == Activity.RESULT_OK) "Android не подтвердил перенос" else "Разрешение отклонено"
            }
            pending = emptyList(); answer = null
            if (result != Activity.RESULT_OK) {
                request.rows.drop(index).forEach { failures[it.key] = "Операция отменена" }
                index = request.rows.size
            }
        }
        while (index < request.rows.size) {
            val ref = request.rows[index]
            if (ref.appDownloadId == null) {
                val candidates = request.rows.drop(index).takeWhile { it.appDownloadId == null }.take(50)
                index += candidates.size
                val valid = mutableListOf<LibraryMediaRef>()
                for (candidate in candidates) {
                    try { verify(candidate); valid += candidate }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { failures[candidate.key] = e.message ?: "Файл недоступен" }
                }
                if (valid.isNotEmpty()) {
                    try { requestSystemTrash(valid); return@LaunchedEffect }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { valid.forEach { failures[it.key] = e.message ?: "Системная корзина недоступна" } }
                }
                continue
            }
            try {
                verify(ref)
                try { onAppTrash(requireNotNull(ref.appDownloadId),requireNotNull(ref.item.savedUri)); success(ref) }
                catch (e: SecurityException) { verify(ref); requestSystemTrash(listOf(ref)); index++; return@LaunchedEffect }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failures[ref.key] = e.message ?: "Не удалось переместить" }
            index++
        }
        if (successful.size == request.rows.size && failures.isEmpty()) {
            try { removeAlbum(); removed = true }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { albumError = e.message ?: "Не удалось удалить альбом" }
        }
        finished = true
    }
    if (finished) AlertDialog(onDismissRequest = onClose, title = { Text(if (removed) "Альбом удалён" else "Альбом сохранён") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("В корзине: ${successful.size} из ${request.rows.size}")
            albumError?.let { Text(it) }
            failures.forEach { (key,reason) ->
                HorizontalDivider()
                Text(request.rows.firstOrNull { it.key == key }?.item?.originalName ?: "Файл")
                Text(reason, style = MaterialTheme.typography.bodySmall)
            }
            if (!removed) Text("Оставшиеся файлы можно обработать повторно из альбома после обновления библиотеки.")
        } }, confirmButton = { TextButton(onClose) { Text("Закрыть") } })
    else if (!waiting) AlertDialog(onDismissRequest = {}, title = { Text("Перемещение в корзину") },
        text = { Text("Обработано: $index из ${request.rows.size}") }, confirmButton = {})
}
