package com.domenota.medialoader.ui.screens.organization

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The screen owns only one note draft; searches expose confirmed file IDs, never note text. */
internal class MediaPassportNotes(
    val addressKey: Any?,
    val searchKey: Any?,
    private val store: MediaPassportNoteStore,
    private val catalog: MediaPassportNoteCatalog,
    private val epoch: Long,
    private val active: () -> Boolean,
    private val validate: suspend (List<String>) -> List<LibraryMediaRef>,
) {
    private fun matches(candidate: LibraryMediaRef, live: LibraryMediaRef): Boolean =
        candidate.key == live.key && candidate.item.savedUri == live.item.savedUri &&
            MediaPassportNoteIdentity.keys(candidate) == MediaPassportNoteIdentity.keys(live)

    private suspend fun requireCurrent(ref: LibraryMediaRef) {
        check(addressKey != null && active()) { "Файл недоступен" }
        check(validate(listOf(ref.key)).singleOrNull()?.let { matches(ref,it) } == true && active()) {
            "Файл недоступен"
        }
    }
    suspend fun read(ref: LibraryMediaRef): String {
        requireCurrent(ref)
        val value = store.read(catalog,ref,epoch)
        check(active()) { "Доступ изменился" }
        return value
    }
    suspend fun save(ref: LibraryMediaRef, expected: String, value: String): String {
        requireCurrent(ref)
        val saved = store.save(catalog,ref,epoch,expected,value)
        check(active()) { "Доступ изменился" }
        return saved
    }
    suspend fun search(query: String): Set<String> {
        if (addressKey == null || !active() || query.isBlank()) return emptySet()
        val hits = store.search(catalog,query,epoch)
        if (hits.isEmpty() || !active()) return emptySet()
        val live = validate(hits.map { it.key }).associateBy { it.key }
        if (!active()) return emptySet()
        return hits.filter { candidate -> live[candidate.key]?.let { matches(candidate,it) } == true }
            .mapTo(linkedSetOf()) { it.item.id }
    }
}

@Composable
internal fun rememberMediaPassportNotes(
    refs: List<LibraryMediaRef>, access: Any?, epoch: Long,
    validate: suspend (List<String>) -> List<LibraryMediaRef>,
): MediaPassportNotes {
    val context = LocalContext.current
    val store = remember(context) { MediaPassportNoteStore(context) }
    val revision by MediaPassportNoteStore.changes.collectAsState()
    val catalog = remember(refs) { MediaPassportNoteCatalog(refs) }
    val address = remember(refs,access,epoch) { access?.let { Any() } }
    val latestAddress by rememberUpdatedState(address)
    val latestValidate by rememberUpdatedState(validate)
    return remember(store,catalog,address,epoch,revision) {
        MediaPassportNotes(address,address?.let { it to revision },store,catalog,epoch,
            active = { address != null && latestAddress === address },
            validate = { latestValidate(it) })
    }
}

@Composable
internal fun MediaPassportNoteEditor(ref: LibraryMediaRef, notes: MediaPassportNotes) {
    val access = notes.addressKey ?: return
    var saved by remember(ref,access) { mutableStateOf<String?>(null) }
    var draft by remember(ref,access) { mutableStateOf<String?>(null) }
    var busy by remember(ref,access) { mutableStateOf(false) }
    var error by remember(ref,access) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref,access) {
        try { saved = notes.read(ref) }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = "Заметка недоступна" }
    }
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text("Заметка",style=MaterialTheme.typography.titleMedium)
        TextButton(onClick={ draft=saved; error=null },enabled=saved!=null&&!busy) {
            Text(if(saved.isNullOrEmpty()) "Добавить" else "Изменить")
        }
    }
    saved?.let { Text(it.ifEmpty { "Нет заметки" },style=MaterialTheme.typography.bodyMedium) }
    error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
    draft?.let { value ->
        AlertDialog(onDismissRequest={if(!busy)draft=null},title={Text("Заметка")},
            text={
                Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value,{draft=it.take(MediaPassportNoteIdentity.MAX_LENGTH)},
                        modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=3,maxLines=8,
                        label={Text("Текст заметки")})
                    error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton={TextButton(enabled=!busy,onClick={
                val previous=saved ?: return@TextButton
                busy=true;error=null
                scope.launch {
                    try { saved=notes.save(ref,previous,value);draft=null }
                    catch(e:CancellationException){throw e}
                    catch(_:Exception){error="Не удалось сохранить заметку"}
                    finally{busy=false}
                }
            }){Text("Сохранить")}},
            dismissButton={TextButton({draft=null},enabled=!busy){Text("Отмена")}})
    }
}
