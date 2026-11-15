package com.domenota.medialoader.ui.screens.music

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import com.domenota.medialoader.ui.components.RecognitionEqualizer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.editor.AudioReplacePolicy
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.recognition.*
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.FullSongSearchDialog
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun TrackRecognitionDialog(source:DownloadEntity,positionMs:Long?,onDismiss:()->Unit,onUpdated:()->Unit){
    val context=LocalContext.current
    val repository=remember(context){MediaRepository.get(context)}
    val scope=rememberCoroutineScope()
    val privacyEpoch by DuplicatePrivacyGuard.changes.collectAsState()
    var match by remember(source.id,source.savedUri){mutableStateOf<TrackRecognitionMatch?>(null)}
    var outcome by remember(source.id,source.savedUri){mutableStateOf<TrackRecognitionOutcome?>(null)}
    var progress by remember{mutableStateOf<TrackRecognitionProgress?>(null)}
    var attempt by remember{mutableIntStateOf(0)}
    var busy by remember{mutableStateOf(true)}
    var rejectionTarget by remember{mutableStateOf<TrackRecognitionMatch?>(null)}
    var rejecting by remember{mutableStateOf(false)}
    var applying by remember{mutableStateOf(false)}
    var error by remember{mutableStateOf<String?>(null)}
    var updateMessage by remember{mutableStateOf<String?>(null)}
    var updated by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf(false)}
    var search by remember{mutableStateOf(false)}
    var pending by remember{mutableStateOf<TrackRecognitionMatch?>(null)}
    val eligible=AudioReplacePolicy.eligible(source)
    val currentMatch=match
    LaunchedEffect(privacyEpoch,currentMatch,source.hidden,source.isPrivate){
        if(source.hidden||source.isPrivate||(currentMatch!=null&&!DuplicatePrivacyGuard.accepts(context,currentMatch.version.privacyEpoch,source.id)))onDismiss()
    }
    LaunchedEffect(source.id,source.savedUri,attempt){
        busy=true;error=null;progress=null;match=null;outcome=null
        try{
            val result=repository.recognizeLocalTrack(source,positionMs){progress=it}
            outcome=result;match=result.match
        }catch(e:CancellationException){throw e}
        catch(e:Exception){error=e.message?.takeIf{it.isNotBlank()}?:"Не удалось распознать трек"}
        finally{busy=false}
    }
    fun reject(result:TrackRecognitionMatch){
        rejectionTarget=result;rejecting=true;busy=true;match=null;outcome=null;progress=null;error=null
        scope.launch {
            try { repository.rejectLocalTrack(result);rejectionTarget=null;attempt++ }
            catch(e:CancellationException){throw e}
            catch(e:Exception){error=e.message?:"Не удалось сохранить отклонение";busy=false}
            finally{rejecting=false}
        }
    }
    fun apply(result:TrackRecognitionMatch){
        applying=true;updateMessage=null
        scope.launch{
            try{
                repository.applyLocalTrackRecognition(source,result)
                updated=true;updateMessage="Данные исходного MP3 обновлены"
                onUpdated()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){updateMessage=e.message?:"Не удалось изменить теги"}
            finally{applying=false;pending=null}
        }
    }
    val grant=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){response->
        val result=pending
        if(response.resultCode==Activity.RESULT_OK&&result!=null)apply(result)
        else{pending=null;updateMessage="Разрешение на изменение файла не получено"}
    }
    fun requestUpdate(result:TrackRecognitionMatch){
        confirm=false
        if(!eligible)return
        if(Build.VERSION.SDK_INT>=30){
            try{
                pending=result
                val request=MediaStore.createWriteRequest(context.contentResolver,listOf(Uri.parse(result.version.uri)))
                grant.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }catch(_:Exception){pending=null;updateMessage="Не удалось запросить разрешение Android"}
        }else apply(result)
    }
    if(search&&currentMatch!=null){
        FullSongSearchDialog(FullSongSource.AUTO,currentMatch.result.artist,currentMatch.result.title,
            currentMatch.result.artworkUrl,"Распознано по звуку исходного трека"){search=false}
    }else Dialog(onDismissRequest={if(!applying&&!rejecting)onDismiss()}){
        Surface(shape=MaterialTheme.shapes.extraLarge,color=MaterialTheme.colorScheme.surface){
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement=Arrangement.spacedBy(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
                Text("Распознавание трека",style=MaterialTheme.typography.titleLarge)
                when{
                    busy->{
                        RecognitionEqualizer()
                        val p=progress
                        Text("Определяем композицию")
                        if(attempt>0)Text("Анализируем другой участок аудио",style=MaterialTheme.typography.bodySmall)
                        if(p!=null)Text("Фрагмент ${p.index+1} из ${p.total}" +
                            (p.startMs?.let { at -> " · ${at/1000} с" + (p.durationMs?.let { " · ${it/1000} с аудио" } ?: "") } ?: ""),
                            style=MaterialTheme.typography.bodySmall)
                    }
                    error!=null->{
                        Text(error.orEmpty(),color=MaterialTheme.colorScheme.error)
                        Button({rejectionTarget?.let(::reject) ?: run {attempt++}},Modifier.fillMaxWidth()){Text("Повторить")}
                    }
                    currentMatch!=null->{
                        val result=currentMatch.result
                        DownloadThumbnail(null,result.artworkUrl,MediaKind.AUDIO,Modifier.size(104.dp))
                        Text(result.title,style=MaterialTheme.typography.titleLarge)
                        Text(result.artist,style=MaterialTheme.typography.titleMedium)
                        result.album?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodyMedium)}
                        if(currentMatch.verification==TrackVerification.CONFIRMED)Text(if(outcome?.conflict==true)"Два фрагмента совпали, но были и другие результаты. Проверьте композицию." else "Совпало на двух независимых фрагментах",style=MaterialTheme.typography.bodySmall)
                        if(currentMatch.verification==TrackVerification.UNCONFIRMED)Text(
                            if(outcome?.conflict==true)"Результаты разных фрагментов расходились. Проверьте композицию." else "Дополнительное совпадение не подтверждено. Проверьте композицию.",
                            style=MaterialTheme.typography.bodySmall)
                        if(applying)LinearProgressIndicator(Modifier.fillMaxWidth())
                        Button({confirm=true},Modifier.fillMaxWidth(),enabled=eligible&&!updated&&!applying&&pending==null){Text("Обновить данные трека")}
                        if(!eligible)Text("Безопасное изменение тегов доступно для MP3 в общей медиатеке. Распознавание и скачивание доступны.",style=MaterialTheme.typography.bodySmall)
                        OutlinedButton({search=true},Modifier.fillMaxWidth(),enabled=!applying&&pending==null){Text("Скачать композицию")}
                        TextButton(onClick={reject(currentMatch)},enabled=!updated&&!applying&&pending==null&&!rejecting,modifier=Modifier.fillMaxWidth()){
                            Icon(Icons.Rounded.Refresh,null);Spacer(Modifier.width(8.dp));Text("Это не тот трек")
                        }
                        if(!updated)Text("Распознать другой фрагмент композиции",style=MaterialTheme.typography.bodySmall)
                        updateMessage?.let{Text(it,style=MaterialTheme.typography.bodySmall,color=if(updated)MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)}
                    }
                    else->{
                        Text(if(outcome?.conflict==true)"Разные фрагменты дали разные совпадения" else if(outcome?.retried==true && outcome?.total==1)"Других непроверенных фрагментов нет" else if(outcome?.retried==true || attempt>0)"Других совпадений не найдено" else "Совпадение не найдено")
                        val result=outcome
                        if(result!=null&&result.nextIndex<result.total)Button({
                            attempt++
                        },Modifier.fillMaxWidth()){Text("Другой фрагмент")}
                    }
                }
                TextButton(onDismiss,enabled=!applying&&!rejecting){Text(if(busy)"Отмена" else "Закрыть")}
            }
        }
    }
    if(confirm&&currentMatch!=null)AlertDialog(onDismissRequest={confirm=false},
        title={Text("Обновить исходный MP3?")},
        text={Text("Будут изменены теги и доступная обложка. Имя файла, записи медиатеки, избранное и плейлисты сохранятся. Перед записью проверяется неизменность аудио.")},
        confirmButton={TextButton({requestUpdate(currentMatch)}){Text("Обновить")}},
        dismissButton={TextButton({confirm=false}){Text("Отмена")}})
}
