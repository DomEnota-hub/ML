package com.domenota.medialoader.ui.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.privatevault.*
import com.domenota.medialoader.ui.components.PrivateAccessGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shares the existing PrivateAccessManager gate, but keeps external-origin files
 * separate from old "Личное" records. Never adds decrypted previews to public caches.
 */
@Composable
internal fun VaultScreen(access:PrivateAccessManager,onBack:()->Unit) {
    val context=LocalContext.current
    val state by access.state.collectAsState()
    val operations=remember(context){VaultOperations(context)}
    val scope=rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var listing by remember { mutableStateOf(VaultIndexSnapshot(emptyList(),0)) }
    var message by remember { mutableStateOf<String?>(null) }
    var pendingSelection by remember { mutableStateOf<List<Uri>?>(null) }
    var safRemovalId by remember { mutableStateOf<String?>(null) }
    var systemDeletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletionResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var deletionApproved by rememberSaveable { mutableStateOf(false) }
    var exportChoiceId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportRemoveFromVault by rememberSaveable { mutableStateOf(false) }
    var exportResultId by rememberSaveable { mutableStateOf<String?>(null) }
    var exportResultUri by remember { mutableStateOf<Uri?>(null) }
    var exportResultRemove by rememberSaveable { mutableStateOf(false) }
    var showUninstallWarning by remember { mutableStateOf(false) }

    fun runAction(block:suspend ()->Unit) {
        if(busy || !access.state.value.unlocked)return
        busy=true
        scope.launch {
            try { block() }
            catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(error:Exception) {
                message=error.message?.take(180) ?: "Действие не выполнено; оригиналы не изменялись"
            } finally { busy=false; revision++ }
        }
    }

    val importer=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if(uris.isNotEmpty()) {
            uris.forEach { uri ->
                runCatching {
                    context.contentResolver.takePersistableUriPermission(
                        uri,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                }.onFailure {
                    // Some providers offer only a read grant; still allow a verified copy.
                    runCatching { context.contentResolver.takePersistableUriPermission(
                        uri,Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                }
            }
            pendingSelection=uris.distinct()
        }
    }
    val mediaConsent=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        deletionResultId=systemDeletionId
        deletionApproved=result.resultCode==Activity.RESULT_OK
        systemDeletionId=null
    }
    val documentPicker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val selectedId=exportChoiceId
        if(result.resultCode==Activity.RESULT_OK && selectedId!=null && result.data?.data!=null) {
            exportResultId=selectedId
            exportResultUri=result.data?.data
            exportResultRemove=exportRemoveFromVault
        }
        exportChoiceId=null
    }

    LaunchedEffect(state.unlocked,revision) {
        listing=if(state.unlocked) withContext(Dispatchers.IO){ operations.snapshot() }
            else VaultIndexSnapshot(emptyList(),0)
        if(!state.unlocked){ message=null; pendingSelection=null; safRemovalId=null }
    }
    LaunchedEffect(state.unlocked,deletionResultId) {
        val id=deletionResultId
        if(!state.unlocked || id==null)return@LaunchedEffect
        deletionResultId=null
        runAction {
            val result=operations.completeMediaConsent(id,deletionApproved)
            message=if(result.phase==VaultImportPhase.FULLY_HIDDEN)
                "Оригинал удалён с подтверждением Android. Файл находится в Сейфе."
                else "Публичный оригинал остался или отсутствие не подтверждено. Скрытие не завершено."
        }
    }
    LaunchedEffect(state.unlocked,exportResultId,exportResultUri) {
        val id=exportResultId
        val uri=exportResultUri
        if(!state.unlocked || id==null || uri==null)return@LaunchedEffect
        val move=exportResultRemove
        exportResultId=null;exportResultUri=null
        runAction {
            val ok=try { operations.export(id,uri,move) }
            catch(error:Exception) {
                // This URI was created *specifically* for this export, not an existing document.
                val cleaned=runCatching{DocumentsContract.deleteDocument(context.contentResolver,uri)}.getOrDefault(false)
                throw IllegalStateException(
                    (error.message?:"Не удалось подтвердить экспорт")+
                        if(cleaned)". Неполная копия удалена."
                        else ". Проверьте выбранный файл: неполная копия могла остаться."
                )
            }
            if(!ok) {
                val cleaned=runCatching{DocumentsContract.deleteDocument(context.contentResolver,uri)}.getOrDefault(false)
                message=if(cleaned)"Контроль размера/хеша не пройден, копия удалена."
                    else "Контроль размера/хеша не пройден. Неполная копия могла остаться в выбранной папке."
            } else message=if(move)"Проверенный файл вынесен из Сейфа."
                else "Резервная копия создана. Она доступна вне Сейфа."
        }
    }

    BackHandler(busy || pendingSelection!=null || safRemovalId!=null) {
        if(!busy){pendingSelection=null;safRemovalId=null}
    }

    Column(Modifier.fillMaxSize().testTag("vault-screen")) {
        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClick={access.lock();onBack()},enabled=!busy) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")
            }
            Icon(Icons.Rounded.Shield,null,tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("Сейф",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
            if(state.unlocked)IconButton(onClick={access.lock()},enabled=!busy) {
                Icon(Icons.Rounded.Lock,"Заблокировать")
            }
        }
        if(!state.unlocked) {
            PrivateAccessGate(access,Modifier.weight(1f))
        } else {
            Column(Modifier.weight(1f)) {
                Card(Modifier.fillMaxWidth().padding(horizontal=12.dp)) {
                    Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text("Защищённое хранение",style=MaterialTheme.typography.titleMedium)
                        Text("Фото, видео, музыка и документы из любых приложений. Сначала создаётся и проверяется зашифрованная копия. Публичный оригинал удаляется отдельно и только с подтверждением.",
                            style=MaterialTheme.typography.bodySmall)
                        Button(onClick={importer.launch(arrayOf("*/*"))},
                            enabled=!busy,modifier=Modifier.fillMaxWidth().testTag("vault-import")) {
                            Icon(Icons.Rounded.UploadFile,null)
                            Spacer(Modifier.width(8.dp))
                            Text("Добавить файлы")
                        }
                        TextButton(onClick={showUninstallWarning=true}) {
                            Text("Резервные копии и удаление приложения")
                        }
                    }
                }
                message?.let {
                    Text(it,Modifier.fillMaxWidth().padding(14.dp),
                        style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if(listing.unreadableRecords>0) Text(
                    "Повреждённых записей: ${listing.unreadableRecords}. Они не удалены; восстановление требует проверки.",
                    Modifier.padding(14.dp),color=MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.fillMaxWidth().weight(1f),
                    contentPadding=PaddingValues(12.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(listing.items.isEmpty()) item {
                        Text("Сейф пока пуст. Выберите файлы через системный проводник.",
                            Modifier.padding(14.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    items(listing.items,key={it.id}) { record ->
                        Card {
                            Column(Modifier.fillMaxWidth().padding(12.dp),
                                verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                Text(record.displayName,style=MaterialTheme.typography.titleMedium)
                                Text(vaultPhaseText(record.phase),
                                    style=MaterialTheme.typography.bodySmall,
                                    color=if(record.phase==VaultImportPhase.FULLY_HIDDEN)
                                        MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${record.bytes} байт",style=MaterialTheme.typography.labelSmall,
                                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                    when(record.phase) {
                                        VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT,
                                        VaultImportPhase.PUBLIC_REMOVAL_FAILED,
                                        VaultImportPhase.AWAITING_PUBLIC_REMOVAL -> {
                                            OutlinedButton(onClick={
                                                runAction {
                                                    val next=if(record.phase==VaultImportPhase.AWAITING_PUBLIC_REMOVAL)
                                                        operations.markAwaitingAsFailed(record.id)
                                                        else record
                                                    val pending=operations.requestRemoval(next.id)
                                                    if(Uri.parse(pending.originalUri).authority==MediaStore.AUTHORITY &&
                                                        android.os.Build.VERSION.SDK_INT>=30) {
                                                        systemDeletionId=pending.id
                                                        mediaConsent.launch(IntentSenderRequest.Builder(
                                                            operations.mediaDeleteIntent(pending.id)).build())
                                                    } else safRemovalId=pending.id
                                                }
                                            },enabled=!busy) { Text("Завершить скрытие") }
                                        }
                                        VaultImportPhase.RESTORE_PENDING -> {
                                            OutlinedButton(onClick={
                                                runAction {
                                                    val done=operations.finishRestoredCleanup(record.id)
                                                    message=if(done)"Очистка завершена" else "Приватная копия ещё не очищена"
                                                }
                                            },enabled=!busy) { Text("Завершить возврат") }
                                        }
                                        VaultImportPhase.FULLY_HIDDEN -> Unit
                                    }
                                    if(record.phase!=VaultImportPhase.RESTORE_PENDING) {
                                        TextButton(onClick={
                                            exportChoiceId=record.id;exportRemoveFromVault=false
                                            documentPicker.launch(vaultCreateDocumentIntent(record.displayName))
                                        },enabled=!busy) { Text("Резервная копия") }
                                    }
                                }
                                if(record.phase!=VaultImportPhase.RESTORE_PENDING) {
                                    OutlinedButton(onClick={
                                        exportChoiceId=record.id;exportRemoveFromVault=true
                                        documentPicker.launch(vaultCreateDocumentIntent(record.displayName))
                                    },enabled=!busy,modifier=Modifier.fillMaxWidth()) {
                                        Icon(Icons.Rounded.FolderOpen,null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Вынести из Сейфа")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if(state.unlocked && pendingSelection!=null) AlertDialog(
        onDismissRequest={pendingSelection=null},
        title={Text("Добавить в Сейф?")},
        text={Text("Выбрано ${pendingSelection!!.size} файлов. Сначала ML создаст защищённые копии. Для полного скрытия понадобится отдельно подтвердить удаление публичных оригиналов.")},
        confirmButton={TextButton(onClick={
            val selected=pendingSelection.orEmpty()
            pendingSelection=null
            runAction {
                val results=operations.importSelected(selected)
                val successes=results.count{it.isSuccess}
                message="Проверенные копии: $successes из ${results.size}. Публичные оригиналы пока остаются на месте."
            }
        }) {Text("Создать копии")}},
        dismissButton={TextButton(onClick={pendingSelection=null}) {Text("Отмена")}}
    )
    if(state.unlocked && safRemovalId!=null) AlertDialog(
        onDismissRequest={safRemovalId=null},
        title={Text("Удалить публичный оригинал?")},
        text={Text("Только выбранный документ. Его проверенная зашифрованная копия останется в Сейфе. Если провайдер запретит удаление, скрытие будет отмечено незавершённым.")},
        confirmButton={TextButton(onClick={
            val id=safRemovalId!!
            safRemovalId=null
            runAction {
                val next=operations.removeSafOriginal(id)
                message=if(next.phase==VaultImportPhase.FULLY_HIDDEN)
                    "Публичный документ удалён; защищённая копия сохранена."
                    else "Удаление не подтверждено. Оригинал оставлен, скрытие не завершено."
            }
        }) {Text("Удалить оригинал")}},
        dismissButton={TextButton(onClick={
            val id=safRemovalId!!
            safRemovalId=null
            runAction { operations.markAwaitingAsFailed(id) }
        }){Text("Оставить оригинал")}}
    )
    if(showUninstallWarning) AlertDialog(
        onDismissRequest={showUninstallWarning=false},
        title={Text("Важно о резервных копиях")},
        text={Text("Файлы Сейфа зашифрованы ключом Android Keystore и находятся внутри ML. При удалении приложения или потере ключа они могут стать безвозвратно недоступны. До удаления ML экспортируйте нужные файлы через «Резервная копия» в выбранное вами безопасное место. Экспорт делает файл публично доступным в выбранном каталоге.")},
        confirmButton={TextButton(onClick={showUninstallWarning=false}){Text("Понятно")}}
    )
}

private fun vaultPhaseText(phase:VaultImportPhase):String=when(phase) {
    VaultImportPhase.COPY_VERIFIED_PUBLIC_PRESENT -> "Копия защищена. Оригинал пока виден в Android"
    VaultImportPhase.AWAITING_PUBLIC_REMOVAL -> "Удаление оригинала требует подтверждения"
    VaultImportPhase.PUBLIC_REMOVAL_FAILED -> "Оригинал не удалён. Скрытие не завершено"
    VaultImportPhase.FULLY_HIDDEN -> "Скрыт из общей медиатеки"
    VaultImportPhase.RESTORE_PENDING -> "Возврат проверен. Требуется завершить очистку"
}

private fun vaultCreateDocumentIntent(name:String):Intent {
    val extension=name.substringAfterLast('.', "").lowercase()
    val mime=MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        ?: "application/octet-stream"
    return Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
        .setType(mime).putExtra(Intent.EXTRA_TITLE,name)
}
