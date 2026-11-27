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

internal data class MediaPassportNoteSearch(val ids: Set<String>, val incomplete: Boolean)

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
        val started=android.os.SystemClock.elapsedRealtime()
        val log=com.domenota.medialoader.core.logging.AppLog
        try{
            requireCurrent(ref)
            val saved=store.save(catalog,ref,epoch,expected,value)
            check(active()) { "Доступ изменился" }
            val action=when{saved==expected->"UNCHANGED";saved.isEmpty()->"DELETE";expected.isEmpty()->"CREATE";else->"EDIT"}
            log.d("MediaNotes","NOTE_SAVE status=COMPLETE action=$action ms=${android.os.SystemClock.elapsedRealtime()-started}")
            return saved
        }catch(cancelled:CancellationException){throw cancelled}
        catch(failure:Exception){
            log.d("MediaNotes","NOTE_SAVE status=UNAVAILABLE ms=${android.os.SystemClock.elapsedRealtime()-started}")
            throw failure
        }
    }
    suspend fun search(query: String): MediaPassportNoteSearch {
        if (query.isBlank()) return MediaPassportNoteSearch(emptySet(),false)
        check(addressKey != null && active()) { "Поиск заметок недоступен" }
        val hits = store.search(catalog,query,epoch)
        check(active()) { "Доступ изменился" }
        if (hits.isEmpty()) return MediaPassportNoteSearch(emptySet(),false)
        val live = validate(hits.map { it.key }).associateBy { it.key }
        check(active()) { "Доступ изменился" }
        val confirmed = hits.filter { candidate -> live[candidate.key]?.let { matches(candidate,it) } == true }
        return MediaPassportNoteSearch(confirmed.mapTo(linkedSetOf()) { it.item.id },confirmed.size != hits.size)
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

/** Direct viewer entry; no note content survives address invalidation or navigation. */
@Composable
internal fun MediaPassportNoteDialog(ref: LibraryMediaRef, notes: MediaPassportNotes, onDismiss: () -> Unit) {
    val access = notes.addressKey ?: return
    var saved by remember(ref,access) { mutableStateOf<String?>(null) }
    var draft by remember(ref,access) { mutableStateOf("") }
    var busy by remember(ref,access) { mutableStateOf(false) }
    var error by remember(ref,access) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref,access) {
        try { notes.read(ref).let { saved=it; draft=it } }
        catch (e: CancellationException) { throw e }
        catch (_: Exception) { error="Заметка недоступна" }
    }
    AlertDialog(onDismissRequest={ if(!busy)onDismiss() },title={Text("Заметки")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                if(saved==null && error==null) Text("Загрузка заметки…")
                if(saved!=null) OutlinedTextField(draft,{draft=it.take(MediaPassportNoteIdentity.MAX_LENGTH)},
                    modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=3,maxLines=8,label={Text("Текст заметки")})
                error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
            }
        },confirmButton={TextButton(enabled=saved!=null&&!busy,onClick={
            val previous=saved ?: return@TextButton
            busy=true;error=null
            scope.launch {
                try { notes.save(ref,previous,draft);onDismiss() }
                catch(e: CancellationException){throw e}
                catch(_: Exception){
                    error="Не удалось сохранить заметку"

                }
                finally{busy=false}
            }
        }){Text("Сохранить")}},
        dismissButton={TextButton(onDismiss,enabled=!busy){Text("Отмена")}})
}
