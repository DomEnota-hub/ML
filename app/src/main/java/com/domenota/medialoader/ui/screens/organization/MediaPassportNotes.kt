package com.domenota.medialoader.ui.screens.organization

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.ui.Alignment
import java.text.DateFormat
import java.util.Date
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class MediaPassportNoteSearch(val ids: Set<String>, val incomplete: Boolean)

/** Screen-local drafts; searches expose confirmed file IDs, never note text. */
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
    suspend fun readNotes(ref: LibraryMediaRef): List<MediaPassportNote> {
        requireCurrent(ref)
        val value = store.readNotes(catalog,ref,epoch)
        check(active()) { "Доступ изменился" }
        return value
    }
    suspend fun saveNotes(ref: LibraryMediaRef, expected: List<MediaPassportNote>, value: List<MediaPassportNote>): List<MediaPassportNote> {
        val started=android.os.SystemClock.elapsedRealtime()
        val log=com.domenota.medialoader.core.logging.AppLog
        try{
            requireCurrent(ref)
            val saved=store.saveNotes(catalog,ref,epoch,expected,value)
            check(active()) { "Доступ изменился" }
            val action=when{saved==expected->"UNCHANGED";saved.size>expected.size->"CREATE";saved.size<expected.size->"DELETE";else->"EDIT"}
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
    MediaPassportNotePanel(ref, notes, null)
}

/** Same independent note list in the audio passport and direct visual viewer dialog. */
@Composable
internal fun MediaPassportNoteDialog(ref: LibraryMediaRef, notes: MediaPassportNotes, onDismiss: () -> Unit) {
    MediaPassportNotePanel(ref, notes, onDismiss)
}

@Composable
private fun MediaPassportNotePanel(ref: LibraryMediaRef, notes: MediaPassportNotes, onDismiss: (() -> Unit)?) {
    val access = notes.addressKey ?: return
    var saved by remember(ref,access) { mutableStateOf<List<MediaPassportNote>?>(null) }
    var editing by remember(ref,access) { mutableStateOf(false) }
    var editId by remember(ref,access) { mutableStateOf<String?>(null) }
    var draft by remember(ref,access) { mutableStateOf("") }
    var deleteId by remember(ref,access) { mutableStateOf<String?>(null) }
    var busy by remember(ref,access) { mutableStateOf(false) }
    var error by remember(ref,access) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(ref,access) {
        try { saved=notes.readNotes(ref) }
        catch(e: CancellationException){throw e}
        catch(_: Exception){error="Заметки недоступны"}
    }
    fun begin(note: MediaPassportNote?) {
        editId=note?.id; draft=note?.text.orEmpty(); editing=true; deleteId=null; error=null
    }
    fun commit(next: List<MediaPassportNote>) {
        val previous=saved ?: return
        if(busy)return
        busy=true;error=null
        scope.launch {
            try {
                saved=notes.saveNotes(ref,previous,next)
                editing=false;editId=null;draft="";deleteId=null
            } catch(e:CancellationException){throw e}
            catch(_:Exception){error="Не удалось сохранить заметки"}
            finally{busy=false}
        }
    }
    val panel: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth(), verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val rows=saved
            if(rows==null && error==null) Text("Загрузка заметок…")
            if(rows!=null) {
                if(rows.isEmpty()) Text("Нет заметок",color=MaterialTheme.colorScheme.onSurfaceVariant)
                rows.forEach { note ->
                    key(note.id) {
                        Surface(shape=RoundedCornerShape(12.dp),color=MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.55f),
                            modifier=Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp)) {
                                Text(note.text,style=MaterialTheme.typography.bodyMedium)
                                Row(verticalAlignment=Alignment.CenterVertically) {
                                    Text(note.createdAt?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(it)) }
                                        ?: "Дата создания неизвестна",Modifier.weight(1f),style=MaterialTheme.typography.labelSmall,
                                        color=MaterialTheme.colorScheme.onSurfaceVariant)
                                    IconButton(onClick={begin(note)},enabled=!busy && !editing && deleteId==null) {
                                        Icon(Icons.Rounded.Edit,"Изменить заметку")
                                    }
                                    IconButton(onClick={deleteId=note.id;error=null},enabled=!busy && !editing && deleteId==null) {
                                        Icon(Icons.Rounded.DeleteOutline,"Удалить заметку",tint=MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
                if(editing) {
                    Text(if(editId==null)"Новая заметка" else "Редактирование",style=MaterialTheme.typography.titleSmall)
                    OutlinedTextField(draft,{draft=it.take(MediaPassportNoteIdentity.MAX_LENGTH)},
                        modifier=Modifier.fillMaxWidth(),enabled=!busy,minLines=3,maxLines=8,label={Text("Текст заметки")})
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton({editing=false;editId=null;draft="";error=null},enabled=!busy){Text("Отмена")}
                        TextButton(onClick={
                            val id=editId
                            val next=if(id==null)rows+MediaPassportNote.create(draft.trim())
                                else rows.map{if(it.id==id)it.copy(text=draft.trim()) else it}
                            commit(next)
                        },enabled=!busy&&draft.isNotBlank()){Text("Сохранить")}
                    }
                } else if(deleteId!=null) {
                    Text("Удалить заметку?",style=MaterialTheme.typography.titleSmall)
                    Text("Медиафайл и остальные заметки сохранятся.",style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.End) {
                        TextButton({deleteId=null;error=null},enabled=!busy){Text("Отмена")}
                        TextButton({commit(rows.filterNot{it.id==deleteId})},enabled=!busy){
                            Text("Удалить",color=MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    OutlinedButton({begin(null)},Modifier.fillMaxWidth(),enabled=!busy) {
                        Icon(Icons.Rounded.Add,null)
                        Spacer(Modifier.width(6.dp))
                        Text("Добавить заметку")
                    }
                }
            }
            error?.let { Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall) }
        }
    }
    if(onDismiss!=null) {
        AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Заметки",Modifier.weight(1f))
                saved?.let{Text(it.size.toString(),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
            }
        },text={Column(Modifier.heightIn(max=440.dp).verticalScroll(rememberScrollState())){panel()}},
            confirmButton={TextButton(onDismiss,enabled=!busy){Text("Закрыть")}})
    } else {
        HorizontalDivider()
        Text("Заметки",style=MaterialTheme.typography.titleMedium)
        panel()
    }
}
