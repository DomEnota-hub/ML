package com.domenota.medialoader.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.editor.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

@Composable
fun BatchPhotoEditorScreen(rows:List<DownloadEntity>,state:BatchPhotoState,busy:Boolean,ready:Boolean,
    onBack:()->Unit,onStart:(List<DownloadEntity>,BatchPhotoSpec)->Unit,onCancel:()->Unit) {
    val context=LocalContext.current
    var side by rememberSaveable{mutableIntStateOf(1920)}
    var format by rememberSaveable{mutableStateOf(BatchPhotoFormat.JPEG.name)}
    var quality by rememberSaveable{mutableIntStateOf(85)}
    var gps by rememberSaveable{mutableStateOf(true)}
    var exif by rememberSaveable{mutableStateOf(false)}
    var effect by rememberSaveable{mutableStateOf(PhotoEffect.NONE.name)}
    var watermark by rememberSaveable{mutableStateOf("")}
    var index by rememberSaveable{mutableIntStateOf(0)}
    var preview by remember{mutableStateOf<android.graphics.Bitmap?>(null)}
    var previewError by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf(false)}
    val spec=BatchPhotoSpec(side,BatchPhotoFormat.valueOf(format),quality,gps,exif,PhotoEffect.valueOf(effect),watermark)
    val row=rows.getOrNull(index.coerceIn(0,(rows.size-1).coerceAtLeast(0)))
    LaunchedEffect(row?.savedUri,spec,state.running){
        preview=null;previewError=false
        if(row?.savedUri==null||state.running)return@LaunchedEffect
        delay(250)
        try{
            val result=BatchPhotoEditor(context).export(Uri.parse(row.savedUri),spec.copy(maxSide=if(side==0)512 else minOf(side,512),removeExif=true))
            try{preview=withContext(Dispatchers.IO){BitmapFactory.decodeFile(result.file.absolutePath)}}
            finally{result.file.delete()}
        }catch(e:CancellationException){throw e}catch(e:Exception){previewError=true}
    }
    BackHandler(state.running){confirm=true}
    val gold=Color(0xFFFFBD19)
    val colors=MaterialTheme.colorScheme.copy(primary=gold,onPrimary=Color.Black,secondary=gold,onSecondary=Color.Black,
        primaryContainer=Color(0xFF594600),onPrimaryContainer=Color.White,secondaryContainer=Color(0xFF594600),onSecondaryContainer=Color.White)
    MaterialTheme(colorScheme=colors){
        Column(Modifier.fillMaxSize().padding(12.dp)){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                TextButton({if(state.running)confirm=true else onBack()}){Text("Назад")}
                Text("Пакетный редактор",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=12.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                Text("Выбрано: ${rows.size} фото")
                if(!ready&&!state.running)Text("Не все выбранные файлы доступны. Вернитесь в галерею.")
                preview?.let{Image(it.asImageBitmap(),"Предпросмотр результата",Modifier.fillMaxWidth().height(220.dp))}
                if(previewError)Text("Предпросмотр недоступен")
                if(!state.running&&rows.isNotEmpty())Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton({index=(index-1).coerceAtLeast(0)},enabled=index>0){Text("Предыдущее")}
                    Text("${index+1} / ${rows.size}")
                    TextButton({index=(index+1).coerceAtMost(rows.lastIndex)},enabled=index<rows.lastIndex){Text("Следующее")}
                }
                Text("Максимальная сторона")
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    listOf(0,1080,1920,3840).forEach{n->FilterChip(selected=side==n,onClick={side=n},enabled=!busy,
                        label={Text(if(n==0)"Исх." else n.toString())})}
                }
                Text("Формат")
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    BatchPhotoFormat.entries.forEach{f->FilterChip(selected=format==f.name,onClick={format=f.name},enabled=!busy,label={Text(f.name)})}
                }
                if(format!="PNG"){Text("Качество: $quality%");Slider(quality.toFloat(),{quality=it.toInt()},valueRange=10f..100f,enabled=!busy)}
                if(format=="JPEG"){
                    Row{Checkbox(gps,{gps=it},enabled=!busy&&!exif);Text("Удалить GPS",Modifier.padding(top=12.dp))}
                    Row{Checkbox(exif,{exif=it},enabled=!busy);Text("Удалить EXIF",Modifier.padding(top=12.dp))}
                    Text("Сохраняются только основные данные камеры и даты; ориентация исправляется.")
                }else Text("PNG и WebP сохраняются без исходных метаданных.")
                Text("Цветовой пресет")
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    listOf(PhotoEffect.NONE,PhotoEffect.MONO,PhotoEffect.VIVID).forEach{f->FilterChip(selected=effect==f.name,onClick={effect=f.name},enabled=!busy,label={Text(f.label)})}
                }
                Row(horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    listOf(PhotoEffect.WARM,PhotoEffect.COOL,PhotoEffect.FILM_FADED).forEach{f->FilterChip(selected=effect==f.name,onClick={effect=f.name},enabled=!busy,label={Text(f.label)})}
                }
                OutlinedTextField(watermark,{watermark=it.take(100)},label={Text("Текст водяного знака")},enabled=!busy,modifier=Modifier.fillMaxWidth())
                Text("Оригиналы сохраняются. Результаты — отдельные файлы.")
                if(state.total>0){
                    Text("Сохранено: ${state.outcomes.count{it.error==null}} / ${state.total}")
                    LinearProgressIndicator(progress={state.outcomes.size.toFloat()/state.total},modifier=Modifier.fillMaxWidth())
                    if(state.cancelled)Text("Обработка отменена. Готовые копии сохранены.")
                    state.failed.forEach{Text("${it.name}: ${it.error}",color=MaterialTheme.colorScheme.error)}
                    if(!busy&&state.failed.isNotEmpty())TextButton({onStart(rows.filter{r->state.failed.any{it.id==r.id}},spec)}){Text("Повторить ошибки")}
                }
            }
            if(state.running)Button(onCancel,Modifier.fillMaxWidth()){Text("Отменить обработку")}
            else Button({onStart(rows,spec)},Modifier.fillMaxWidth(),enabled=ready&&!busy&&rows.isNotEmpty()){Text("Обработать фото")}
        }
        if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Обработка продолжается")},
            text={Text("Можно вернуться в галерею. Обработка продолжится в фоне.")},
            confirmButton={TextButton({confirm=false;onBack()}){Text("В галерею")}},
            dismissButton={TextButton({confirm=false}){Text("Остаться")}})
    }
}
