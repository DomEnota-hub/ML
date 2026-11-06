package com.domenota.medialoader.ui.screens.gallery

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phone albums are physical MediaStore folders. MediaLoader folders are logical collections.
 * Do not silently convert a request to move an original into a copy operation.
 */
internal object GalleryAlbumMovePolicy {
    fun isPhoneMedia(uri: String?): Boolean = runCatching {
        val parsed = Uri.parse(uri)
        parsed.scheme == "content" && parsed.authority == MediaStore.AUTHORITY
    }.getOrDefault(false)

    fun remainingSelection(all: List<String>, completed: Set<String>): List<String> =
        all.filterNot(completed::contains)

    fun summary(total: Int, completed: Int, invalid: Int): String =
        if (invalid == 0) "В альбом перенесено: $completed из $total"
        else "Перенесено: $completed из $total. Не удалось: $invalid. Необработанные файлы остались выделенными."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryBulkAlbumMoveSheet(
    selected: List<DownloadEntity>,
    albums: List<GalleryAlbum>,
    deviceAlbums: Map<String, DeviceAlbum>,
    onCreateFolder: suspend (String) -> String,
    onSetFolder: suspend (String, String?) -> Unit,
    onDismiss: () -> Unit,
    onFinished: (Set<String>, String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var search by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var phoneTarget by remember { mutableStateOf<GalleryAlbum?>(null) }
    val selectedIds = remember(selected) { selected.map { it.id } }

    // The phone writer owns the user-granted write request and processes immutable IDs.
    phoneTarget?.let { target ->
        GalleryPhoneAlbumMoveHost(
            selected = selected, target = target, deviceAlbums = deviceAlbums,
            onFinished = { completed, note ->
                onFinished(completed, note ?: GalleryAlbumMovePolicy.summary(
                    selected.size, completed.size, selected.size - completed.size))
            },
        )
        return
    }

    fun assignToFolder(folderId: String) {
        if (busy) return
        busy = true
        scope.launch {
            val completed = mutableSetOf<String>()
            val failed = mutableSetOf<String>()
            try {
                selectedIds.forEach { id ->
                    try {
                        onSetFolder(id, folderId)
                        completed += id
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed += id }
                }
                onFinished(completed, GalleryAlbumMovePolicy.summary(
                    selectedIds.size, completed.size, failed.size))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                busy = false
                problem = failure.message ?: "Не удалось переместить файлы"
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 18.dp)) {
            Text("Переместить в альбом", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text("Выбрано: ${selected.size}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск альбома") }, singleLine = true,
                enabled = !busy)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                items(albums.filter { it.name.contains(search, ignoreCase = true) },
                    key = { it.key }) { album ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            chosen = album.key
                            creating = false
                            problem = null
                        }.padding(vertical = 11.dp, horizontal = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(if (album.key.startsWith("device:")) Icons.Rounded.FolderOpen
                            else Icons.Rounded.Folder, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(album.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${album.count} файлов · ${album.origin}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (!creating && chosen == album.key) {
                            Icon(Icons.Rounded.CheckCircle, "Выбрано",
                                tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                item(key = "new-folder") {
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = !busy) {
                            creating = true
                            chosen = null
                            problem = null
                        }.padding(vertical = 14.dp, horizontal = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.CreateNewFolder, null,
                            tint = MaterialTheme.colorScheme.primary)
                        Text("Создать папку MediaLoader")
                    }
                }
            }
            if (creating) {
                OutlinedTextField(newName, { newName = it }, Modifier.fillMaxWidth(),
                    label = { Text("Название новой папки") }, singleLine = true,
                    enabled = !busy)
            }
            Text(
                if (chosen?.startsWith("device:") == true)
                    "Альбом телефона: оригиналы будут физически перенесены. Android запросит разрешение."
                else "Папка MediaLoader: файлы попадут в коллекцию без изменения расположения оригиналов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            problem?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onDismiss() }, enabled = !busy) { Text("Отмена") }
                Button(
                    onClick = {
                        if (creating) {
                            busy = true
                            scope.launch {
                                try {
                                    val folderId = onCreateFolder(newName.trim())
                                    busy = false
                                    assignToFolder(folderId)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) {
                                    busy = false
                                    problem = failure.message ?: "Не удалось создать папку"
                                }
                            }
                        } else {
                            val target = albums.firstOrNull { it.key == chosen }
                            if (target == null) problem = "Выбери альбом"
                            else if (target.key.startsWith("folder:")) {
                                assignToFolder(target.key.removePrefix("folder:"))
                            } else if (target.key.startsWith("device:")) {
                                phoneTarget = target
                            }
                        }
                    },
                    enabled = !busy && (if (creating) newName.isNotBlank() else chosen != null),
                ) { Text(if (busy) "Переносим…" else "Переместить ${selected.size}") }
            }
        }
    }
}

/** No copying or deleting. Android MediaStore only updates RELATIVE_PATH after system consent. */
@Composable
private fun GalleryPhoneAlbumMoveHost(
    selected: List<DownloadEntity>,
    target: GalleryAlbum,
    deviceAlbums: Map<String, DeviceAlbum>,
    onFinished: (Set<String>, String?) -> Unit,
) {
    val context = LocalContext.current
    val albumId = target.key.removePrefix("device:")
    val alreadyThere = remember(selected, deviceAlbums, albumId) {
        selected.filter { deviceAlbums[it.id]?.id == albumId }.mapTo(mutableSetOf()) { it.id }
    }
    val invalid = remember(selected, alreadyThere) {
        selected.filterNot { it.id in alreadyThere || GalleryAlbumMovePolicy.isPhoneMedia(it.savedUri) }
            .mapTo(mutableSetOf()) { it.id }
    }
    val batches = remember(selected, alreadyThere, invalid) {
        selected.filter { it.id !in alreadyThere && it.id !in invalid }.chunked(200)
    }
    var targetPath by remember(target.key) { mutableStateOf<String?>(null) }
    var fatal by remember(target.key) { mutableStateOf<String?>(null) }
    var batchIndex by remember(target.key) { mutableIntStateOf(0) }
    var authorized by remember(target.key) { mutableStateOf(false) }
    var completed by remember(target.key) { mutableStateOf(alreadyThere.toSet()) }
    var failed by remember(target.key) { mutableStateOf(invalid.toSet()) }
    var requesting by remember(target.key) { mutableStateOf(false) }
    val writePermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()) { answer ->
        requesting = false
        if (answer.resultCode == Activity.RESULT_OK) authorized = true
        else fatal = "Разрешение на изменение файлов не предоставлено."
    }
    LaunchedEffect(target.key) {
        try {
            val cover = target.cover?.savedUri?.takeIf(GalleryAlbumMovePolicy::isPhoneMedia)
                ?: error("Не удалось определить папку альбома телефона")
            val path = withContext(Dispatchers.IO) {
                context.contentResolver.query(
                    Uri.parse(cover),
                    arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                    null, null, null,
                )?.use { cursor ->
                    if (!cursor.moveToFirst()) null
                    else cursor.getString(cursor.getColumnIndexOrThrow(
                        MediaStore.MediaColumns.RELATIVE_PATH))
                }
            }?.trim()?.trimStart('/')
            require(!path.isNullOrBlank() && !path.contains("..")) {
                "Не удалось определить безопасный путь альбома"
            }
            targetPath = path.trimEnd('/') + "/"
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            fatal = failure.message ?: "Альбом телефона недоступен"
        }
    }
    LaunchedEffect(batchIndex, authorized, targetPath, fatal) {
        val destination = targetPath ?: return@LaunchedEffect
        if (fatal != null || requesting) return@LaunchedEffect
        if (batchIndex >= batches.size) {
            onFinished(completed, GalleryAlbumMovePolicy.summary(
                selected.size, completed.size, selected.size - completed.size))
            return@LaunchedEffect
        }
        val batch = batches[batchIndex]
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            fatal = "Для переноса файлов телефона требуется Android 11 или новее. Оригиналы не изменены."
            return@LaunchedEffect
        }
        if (!authorized) {
            try {
                val pending = MediaStore.createWriteRequest(context.contentResolver,
                    batch.map { Uri.parse(requireNotNull(it.savedUri)) })
                requesting = true
                writePermission.launch(IntentSenderRequest.Builder(pending.intentSender).build())
            } catch (failure: Exception) {
                fatal = "Не удалось запросить разрешение Android: ${failure.message.orEmpty()}"
            }
            return@LaunchedEffect
        }
        val (succeeded, rejected) = withContext(Dispatchers.IO) {
            val succeeded = mutableSetOf<String>()
            val rejected = mutableSetOf<String>()
            for (item in batch) {
                try {
                    val uri = Uri.parse(requireNotNull(item.savedUri))
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, destination)
                    }
                    if (context.contentResolver.update(uri, values, null, null) > 0) succeeded += item.id
                    else rejected += item.id
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { rejected += item.id }
            }
            succeeded to rejected
        }
        completed = completed + succeeded
        failed = failed + rejected
        authorized = false
        batchIndex++
    }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Перенос в «${target.name}»") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (fatal == null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(if (requesting) "Подтверди доступ в системном окне Android"
                         else "Обработано: ${completed.size + failed.size} из ${selected.size}")
                } else {
                    Text(fatal.orEmpty(), color = MaterialTheme.colorScheme.error)
                    Text("Уже перенесённые файлы не обрабатываются повторно. Остальные останутся выделенными.")
                }
            }
        },
        confirmButton = {
            if (fatal != null) TextButton(onClick = {
                onFinished(completed, GalleryAlbumMovePolicy.summary(
                    selected.size, completed.size, selected.size - completed.size) + " " + fatal)
            }) { Text("Закрыть") }
        },
    )
}
