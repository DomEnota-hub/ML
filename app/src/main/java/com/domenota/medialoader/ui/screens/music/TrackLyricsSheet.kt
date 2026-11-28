package com.domenota.medialoader.ui.screens.music

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.playback.*
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import com.domenota.medialoader.ui.components.NoticeSheet
import com.domenota.medialoader.ui.theme.musicPlaybackAccent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TrackLyricsSheet(
    player:Player, identity:PlaybackDurationIdentity, positionMs:Long, durationMs:Long,
    seekAvailable:Boolean, blocked:Set<String>, permissionEpoch:Long,
    resolve:suspend (MediaItem)->LibraryMediaRef?, onDismiss:()->Unit,
) {
    val context=LocalContext.current
    val store=remember(context){LocalTrackLyrics(context)}
    val scope=rememberCoroutineScope()
    val latestResolve by rememberUpdatedState(resolve)
    val latestBlocked by rememberUpdatedState(blocked)
    val privacyEpoch by DuplicatePrivacyGuard.changes.collectAsState()
    val recovery by HistoricalRecoverySignals.isRunning.collectAsState()
    val key=remember(identity,blocked,privacyEpoch,permissionEpoch,recovery){Any()}
    val latestKey by rememberUpdatedState(key)
    val alive=remember(key){AtomicBoolean(true)}
    DisposableEffect(alive){ onDispose{alive.set(false)} }
    var lyrics by remember(key){mutableStateOf<TrackLyrics?>(null)}
    var access by remember(key){mutableStateOf<AudioBookmarkAdmission?>(null)}
    var loading by remember(key){mutableStateOf(true)}
    var busy by remember(key){mutableStateOf(false)}
    var message by remember(key){mutableStateOf<String?>(null)}
    var correction by remember(key){mutableLongStateOf(0L)}
    var follow by remember(key){mutableStateOf(true)}
    var networkTarget by remember(key){mutableStateOf<NetworkLyricsTarget?>(null)}
    var candidates by remember(key){mutableStateOf<List<NetworkLyricsHit>>(emptyList())}
    var settingsOpen by remember(key){mutableStateOf(false)}
    var manualPrepared by remember(key){mutableStateOf(false)}
    var manualOpen by remember(key){mutableStateOf(false)}
    var manualTitle by remember(key){mutableStateOf("")}
    var manualArtist by remember(key){mutableStateOf("")}
    // Deliberately keep the pending request outside the identity-keyed state.
    // A returned SAF result can never be rebound to a different track.
    var pending by remember {mutableStateOf<Any?>(null)}
    fun currentMedia():MediaItem?=player.currentMediaItem?.takeIf {
        !recovery && AudioPlaybackMetadata.durationIdentity(it)==identity &&
            !PrivatePlaybackPolicy.blocked(it,false,latestBlocked) &&
            !PrivatePlaybackPolicy.blocked(it,false,DuplicatePrivacyGuard.currentBlockedKeys())
    }
    suspend fun authorize():AudioBookmarkAdmission {
        val media=currentMedia() ?: error("Трек изменился")
        val epoch=DuplicatePrivacyGuard.generation(context)
        val ref=latestResolve(media) ?: error("Трек недоступен")
        check(currentMedia()!=null && key===latestKey && alive.get())
        return AudioBookmarkAdmission(ref,epoch).also{it.requireCurrent(context)}
    }
    suspend fun read(verified:AudioBookmarkAdmission, document:Uri?=null):TrackLyrics? {
        val job=currentCoroutineContext()[Job]
        return withContext(Dispatchers.IO) {
            val allowed={alive.get() && job?.isActive!=false}
            if(document==null) store.read(verified,allowed) else store.importDocument(verified,document,allowed)
        }
    }
    suspend fun lookup(verified:AudioBookmarkAdmission,manual:Pair<String,String>?=null) {
        val job=currentCoroutineContext()[Job]
        val allowed={alive.get() && job?.isActive!=false}
        val target=withContext(Dispatchers.IO){store.networkTarget(verified,manual,allowed)}
        if(key!==latestKey || currentMedia()==null)return
        if(target==null){message="Недостаточно подтверждённых метаданных для поиска";manualOpen=true;settingsOpen=true;return}
        if(manual==null){manualTitle=target.signature.title;manualArtist=target.signature.artist;manualPrepared=true}
        manualOpen=false
        networkTarget=target
        val result=LrclibLyricsProvider.shared.search(target.signature){
            store.networkCurrent(verified,target,allowed)
        }
        if(key!==latestKey || currentMedia()==null)return
        when(result) {
            is LyricsLookup.Matches -> {
                val exact=if(manual==null)LrclibProtocol.automatic(target.signature,result.hits) else null
                if(exact?.candidate?.instrumental==true){message="Инструментальная композиция";return}
                val found=exact?.lyrics
                if(found!=null) {
                    val saved=withContext(Dispatchers.IO){store.saveNetwork(verified,target,found,allowed)}
                    if(key===latestKey && currentMedia()!=null){
                        if(saved!=null){lyrics=saved;message=null}
                        else message="Не удалось сохранить текст"
                    }
                } else {
                    candidates=result.hits.filter{it.lyrics!=null}
                    message=if(candidates.isEmpty())"Текст не найден" else null
                    if(candidates.isNotEmpty())settingsOpen=true
                }
            }
            is LyricsLookup.RetryLater -> message="LRCLIB временно ограничил запросы. Повтор через ${result.seconds} с или позже."
            LyricsLookup.Unavailable -> message="LRCLIB временно недоступен"
            LyricsLookup.Revoked -> message="Трек недоступен"
        }
    }
    LaunchedEffect(key) {
        loading=true
        try {
            val verified=authorize()
            val found=read(verified)
            if(currentMedia()!=null && key===latestKey) {
                access=verified;lyrics=found
                if(found==null){busy=true;try{lookup(verified)}finally{if(key===latestKey)busy=false}}
            }
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){if(key===latestKey)message="Трек недоступен"}
        finally {if(key===latestKey)loading=false}
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){document->
        val requested=pending
        pending=null
        if(document!=null && requested===key && currentMedia()!=null && !busy) {
            busy=true;message=null
            scope.launch {
                try {
                    val verified=authorize()
                    val result=read(verified,document)
                    if(key===latestKey && currentMedia()!=null) {
                        if(result!=null){lyrics=result;access=verified;correction=0L;candidates=emptyList();networkTarget=null}
                        else message="Не удалось прочитать или привязать файл. Выберите LRC или TXT в UTF-8."
                    }
                } catch(cancelled:CancellationException){throw cancelled}
                catch(_:Exception){if(key===latestKey)message="Не удалось импортировать текст"}
                finally {if(key===latestKey)busy=false}
            }
        }
    }
    val ready=access!=null && currentMedia()!=null && !loading
    val data=lyrics?.takeIf{ready}
    val plainLines=remember(data){data?.plain?.lines().orEmpty()}
    val listState=rememberLazyListState()
    val active=data?.active(positionMs,correction) ?: -1
    LaunchedEffect(data,active,follow) {
        if(follow && active>=0)listState.animateScrollToItem(active)
    }
    fun seek(index:Int) {
        val target=data?.seek(index,correction,durationMs) ?: return
        if(!ready || busy || !seekAvailable) return
        scope.launch {
            try {
                val verified=authorize()
                check(verified.keys.any{it in access?.keys.orEmpty()} && currentMedia()!=null)
                val actual=player.duration.takeIf{it>0} ?: durationMs
                if(target<actual && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)) player.seekTo(target)
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(key===latestKey)message="Позиция недоступна"}
        }
    }
    NoticeSheet(onDismissRequest=onDismiss) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=20.dp).padding(bottom=20.dp),
            verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text("Текст песни",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge)
                IconButton(onClick={settingsOpen=!settingsOpen}){
                    Icon(Icons.Rounded.Settings,if(settingsOpen)"Свернуть настройки текста" else "Настройки текста")
                }
            }
            if(loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(data!=null) {
                if(data.timed.isNotEmpty()) {
                    if(settingsOpen)Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Автопрокрутка",style=MaterialTheme.typography.bodyMedium)
                        Switch(checked=follow,onCheckedChange={follow=it})
                    }
                    LazyColumn(state=listState,modifier=Modifier.fillMaxWidth().heightIn(min=120.dp,max=360.dp),
                        verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(data.timed){index,line->
                            Text(line.text,
                                modifier=Modifier.fillMaxWidth().clickable(enabled=seekAvailable && data.seek(index,correction,durationMs)!=null){seek(index)}.padding(vertical=9.dp),
                                style=MaterialTheme.typography.titleMedium,
                                color=if(index==active)musicPlaybackAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight=if(index==active)FontWeight.SemiBold else FontWeight.Normal)
                        }
                    }
                    if(settingsOpen) {
                    Text("Сдвиг синхронизации",style=MaterialTheme.typography.labelMedium)
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(onClick={correction=(correction-250).coerceAtLeast(-60000)}){Text("− 0,25 с")}
                        TextButton(onClick={correction=0L}){Text(String.format(java.util.Locale.getDefault(),"%+.2f с",correction/1000.0))}
                        TextButton(onClick={correction=(correction+250).coerceAtMost(60000)}){Text("+ 0,25 с")}
                    }
                    }
                } else LazyColumn(Modifier.fillMaxWidth().heightIn(max=360.dp)) {
                    itemsIndexed(plainLines){_,line->Text(line,Modifier.padding(vertical=3.dp),style=MaterialTheme.typography.bodyLarge)}
                }
            } else if(!loading && !busy && candidates.isEmpty() && message==null) Text(if(ready)"Текст песни не найден" else "Нет подтверждённого доступа к треку.",
                color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(candidates.isNotEmpty() && ready && !busy && settingsOpen) {
                Text("Выбрать совпадение",style=MaterialTheme.typography.titleSmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max=200.dp)) {
                    itemsIndexed(candidates.flatMap{hit->hit.variants.map{hit to it}}){_,entry->
                        val (hit,version)=entry
                        val signature=hit.candidate.signature
                        TextButton(onClick={
                            val target=networkTarget ?: return@TextButton
                            busy=true;message=null
                            scope.launch {
                                try {
                                    val verified=authorize()
                                    val job=currentCoroutineContext()[Job]
                                    val found=withContext(Dispatchers.IO){
                                        store.replaceNetwork(verified,target,version){alive.get() && job?.isActive!=false}
                                    }
                                    if(key===latestKey && currentMedia()!=null){
                                        if(found!=null){lyrics=found;candidates=emptyList();correction=0L}
                                        else message="Не удалось сохранить текст"
                                    }
                                } catch(cancelled:CancellationException){throw cancelled}
                                catch(_:Exception){if(key===latestKey)message="Трек недоступен"}
                                finally{if(key===latestKey)busy=false}
                            }
                        },modifier=Modifier.fillMaxWidth()){
                            Column {
                                Text(signature.title+" · "+signature.artist)
                                Text(if(version.timed.isEmpty())"Обычный текст" else "С синхронизацией",style=MaterialTheme.typography.labelMedium)
                                Text(signature.album.orEmpty()+" · "+(signature.durationMs/1000)+" с",style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            if(ready && !busy && settingsOpen) {
                TextButton(onClick={
                    manualOpen=!manualOpen
                    if(manualOpen && !manualPrepared){
                        manualPrepared=true;busy=true
                        scope.launch {
                            try {
                                val verified=authorize()
                                val job=currentCoroutineContext()[Job]
                                val target=withContext(Dispatchers.IO){store.networkTarget(verified,null){alive.get() && job?.isActive!=false}}
                                if(key===latestKey && currentMedia()!=null && target!=null){
                                    manualTitle=target.signature.title;manualArtist=target.signature.artist
                                }
                            } catch(cancelled:CancellationException){throw cancelled}
                            catch(_:Exception){if(key===latestKey)message="Метаданные недоступны"}
                            finally{if(key===latestKey)busy=false}
                        }
                    }
                },modifier=Modifier.fillMaxWidth()){
                    Text(if(manualOpen)"Свернуть поиск" else if(data!=null)"Сменить версию текста" else "Найти в LRCLIB")
                }
                if(manualOpen){
                    OutlinedTextField(value=manualTitle,onValueChange={if(it.length<=512)manualTitle=it},
                        label={Text("Название")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedTextField(value=manualArtist,onValueChange={if(it.length<=512)manualArtist=it},
                        label={Text("Исполнитель")},singleLine=true,modifier=Modifier.fillMaxWidth())
                    OutlinedButton(onClick={
                        val query=manualTitle to manualArtist
                        busy=true;message=null;candidates=emptyList()
                        scope.launch {
                            try{lookup(authorize(),query)}
                            catch(cancelled:CancellationException){throw cancelled}
                            catch(_:Exception){if(key===latestKey)message="Поиск недоступен"}
                            finally{if(key===latestKey)busy=false}
                        }
                    },enabled=manualTitle.isNotBlank() && manualArtist.isNotBlank(),
                        modifier=Modifier.fillMaxWidth()){Text("Поиск")}
                }
            }
            message?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
            if(settingsOpen)OutlinedButton(onClick={pending=key;picker.launch(arrayOf("*/*"))},
                enabled=ready && !busy && pending==null,modifier=Modifier.fillMaxWidth()){Text("Импортировать текст")}
        }
    }
}
