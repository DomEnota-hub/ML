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
    var confirm by remember{mutableStateOf<AudioMetadataAction?>(null)}
    var search by remember{mutableStateOf(false)}
    var pending by remember{mutableStateOf<TrackRecognitionMatch?>(null)}
    val eligible=AudioReplacePolicy.eligible(source)
    val currentMatch=match
    var format by remember(currentMatch?.version){mutableStateOf<AudioFileFormat?>(null)}
    var formatError by remember(currentMatch?.version){mutableStateOf<String?>(null)}
    val action=format?.action(eligible)
    LaunchedEffect(currentMatch?.version){
        if(currentMatch!=null)try{format=repository.inspectLocalTrackFormat(source,currentMatch)}
        catch(e:CancellationException){throw e}
        catch(_:Exception){formatError="Не удалось подтвердить фактический аудиоформат"}
    }
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
    fun apply(result:TrackRecognitionMatch,operation:AudioMetadataAction){
        applying=true;updateMessage=null
        scope.launch{
            try{
                when(operation){
                    AudioMetadataAction.REPLACE_MP3->repository.applyLocalTrackRecognition(source,result)
                    AudioMetadataAction.TAGGED_COPY,AudioMetadataAction.CONVERT_MP3->repository.saveRecognizedAudioCopy(source,result,operation==AudioMetadataAction.CONVERT_MP3)
                    AudioMetadataAction.UNAVAILABLE->error("Формат недоступен")
                }
                updated=true;updateMessage=if(operation==AudioMetadataAction.REPLACE_MP3)"Данные исходного MP3 обновлены" else "Копия сохранена. Оригинал не изменён"
                onUpdated()
            }catch(e:CancellationException){throw e}
            catch(e:Exception){updateMessage=e.message?:"Не удалось изменить теги"}
            finally{applying=false;pending=null}
        }
    }
    val grant=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()){response->
        val result=pending
        if(response.resultCode==Activity.RESULT_OK&&result!=null)apply(result,AudioMetadataAction.REPLACE_MP3)
        else{pending=null;updateMessage="Разрешение на изменение файла не получено"}
    }
    fun requestUpdate(result:TrackRecognitionMatch,operation:AudioMetadataAction){
        confirm=null
        if(operation!=AudioMetadataAction.REPLACE_MP3){
            if(operation==AudioMetadataAction.TAGGED_COPY&&action==operation || operation==AudioMetadataAction.CONVERT_MP3&&format?.canConvert==true)apply(result,operation)
            return
        }
        if(!eligible||action!=operation)return
        if(Build.VERSION.SDK_INT>=30){
            try{
                pending=result
                val request=MediaStore.createWriteRequest(context.contentResolver,listOf(Uri.parse(result.version.uri)))
                grant.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }catch(_:Exception){pending=null;updateMessage="Не удалось запросить разрешение Android"}
        }else apply(result,operation)
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
                        format?.let{Text("Формат: ${it.label}",style=MaterialTheme.typography.bodySmall)}
                        if(format==null)Text(formatError?:"Проверка формата…",style=MaterialTheme.typography.bodySmall)
                        if(action!=null&&action!=AudioMetadataAction.UNAVAILABLE){
                            Button({confirm=action},Modifier.fillMaxWidth(),enabled=!updated&&!applying&&pending==null){Text(when(action){
                                AudioMetadataAction.REPLACE_MP3->"Обновить данные трека"
                                AudioMetadataAction.TAGGED_COPY->"Сохранить копию с тегами"
                                else->"Создать копию MP3"
                            })}
                            if(action==AudioMetadataAction.TAGGED_COPY&&format?.canConvert==true&&!updated&&updateMessage!=null&&!applying)
                                TextButton({confirm=AudioMetadataAction.CONVERT_MP3}){Text("Конвертировать копию в MP3")}
                        }else if(action==AudioMetadataAction.UNAVAILABLE)Text("Безопасная запись тегов недоступна для этого формата. Оригинал сохранён.",style=MaterialTheme.typography.bodySmall)
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
    val confirmedAction=confirm
    if(confirmedAction!=null&&currentMatch!=null)AlertDialog(onDismissRequest={confirm=null},
        title={Text(when(confirmedAction){
            AudioMetadataAction.REPLACE_MP3->"Обновить исходный MP3?"
            AudioMetadataAction.TAGGED_COPY->"Сохранить копию с тегами?"
            else->"Создать копию MP3?"
        })},
        text={Text(when(confirmedAction){
            AudioMetadataAction.REPLACE_MP3->"Будут изменены теги и доступная обложка. Имя файла, записи медиатеки, избранное и плейлисты сохранятся. Перед записью проверяется неизменность аудио."
            AudioMetadataAction.TAGGED_COPY->"Копия в формате ${format?.tagFormat?.extension?.uppercase().orEmpty()} без перекодирования. Исходный файл, история, избранное и плейлисты остаются без изменений."
            else->"Копия MP3, 192 кбит/с. Перекодирование с потерями. Исходный файл, история, избранное и плейлисты сохраняются."
        })},
        confirmButton={TextButton({requestUpdate(currentMatch,confirmedAction)}){Text(if(confirmedAction==AudioMetadataAction.REPLACE_MP3)"Обновить" else "Сохранить копию")}},
        dismissButton={TextButton({confirm=null}){Text("Отмена")}})
}
