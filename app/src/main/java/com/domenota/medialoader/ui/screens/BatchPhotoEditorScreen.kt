package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import com.domenota.medialoader.ui.components.EditorSlider as Slider
import com.domenota.medialoader.ui.components.EditorTextDialog
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
import kotlinx.coroutines.delay
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
    var strength by rememberSaveable{mutableFloatStateOf(100f)}
    var category by rememberSaveable{mutableStateOf(PhotoEffectCategory.ALL.name)}
    var compare by rememberSaveable{mutableStateOf(false)}
    var watermarkDraft by rememberSaveable{mutableStateOf("[]")}
    val watermarkLayers=remember(watermarkDraft){BatchWatermarkDraft.decode(watermarkDraft)}
    var editWatermark by rememberSaveable{mutableStateOf(false)}
    var index by rememberSaveable{mutableIntStateOf(0)}
    var preview by remember{mutableStateOf<Bitmap?>(null)}
    var loadedSource by remember{mutableStateOf<Pair<String,Bitmap>?>(null)}
    val original=loadedSource?.second
    var previewIdentity by remember{mutableStateOf<String?>(null)}
    val cache=remember{BatchPhotoPreviewCache()}
    DisposableEffect(cache){onDispose{cache.clear()}}
    var previewError by remember{mutableStateOf(false)}
    var confirm by remember{mutableStateOf(false)}
    val spec=BatchPhotoSpec(side,BatchPhotoFormat.valueOf(format),quality,gps,exif,PhotoEffect.valueOf(effect),
        effectStrength=strength,watermarkLayers=watermarkLayers)
    val row=rows.getOrNull(index.coerceIn(0,(rows.size-1).coerceAtLeast(0)))
    // Quality changes affect encoding only. They must never remove or resize the preview.
    val sourceKey="${row?.id}:${row?.savedUri}:${row?.sizeBytes}:${row?.createdAtEpochMillis}:${row?.isPrivate}"
    LaunchedEffect(sourceKey){
        val identity="${row?.id}:${row?.savedUri}:${row?.isPrivate}"
        if(previewIdentity!=identity){loadedSource=null;preview=null;previewIdentity=identity}
        previewError=false
        if(row?.savedUri==null)return@LaunchedEffect
        try{
            val uri=Uri.parse(row.savedUri)
            val versionKey="$sourceKey:${batchPhotoSourceRevision(context,uri)}"
            loadedSource=versionKey to cache.image("source:$versionKey"){decodeBatchPreview(context,uri)}
        }
        catch(e:CancellationException){throw e}catch(e:Exception){previewError=true}
    }
    val renderSourceKey=loadedSource?.first?:sourceKey
    val renderSpec=spec.copy(quality=85,removeGps=true,removeExif=true)
    LaunchedEffect(original,renderSourceKey,renderSpec){
        val source=original?:return@LaunchedEffect
        previewError=false
        delay(100)
        try{preview=cache.image("preview:$renderSourceKey:$renderSpec"){renderBatchPreview(context,source,renderSpec,640)}}
        catch(e:CancellationException){throw e}catch(e:Exception){previewError=true}
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
                if(busy&&!state.running)Text("Другой экспорт уже выполняется")
                if(!ready&&!state.running)Text("Не все выбранные файлы доступны.")
                Box(Modifier.fillMaxWidth().height(220.dp).testTag("batch-preview"),contentAlignment=Alignment.Center){
                    val shown=if(compare)original else preview
                    if(shown!=null)Image(shown.asImageBitmap(),if(compare)"Оригинал" else "Предпросмотр результата",
                        Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
                    else if(previewError)Text("Предпросмотр недоступен")
                    else if(row!=null)CircularProgressIndicator(Modifier.size(28.dp))
                }
                FilterChip(selected=compare,onClick={compare=!compare},label={Text("Оригинал")},enabled=original!=null)
                if(!state.running&&rows.isNotEmpty())Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween){
                    TextButton({index=(index-1).coerceAtLeast(0)},enabled=index>0){Text("Предыдущее")}
                    Text("${index+1} / ${rows.size}")
                    TextButton({index=(index+1).coerceAtMost(rows.lastIndex)},enabled=index<rows.lastIndex){Text("Следующее")}
                }
                Text("Максимальная сторона")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    listOf(0,1080,1920,3840).forEach{n->FilterChip(selected=side==n,onClick={side=n},enabled=!busy,
                        label={Text(if(n==0)"Исх." else n.toString())})}
                }
                Text("Формат")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    BatchPhotoFormat.entries.forEach{f->FilterChip(selected=format==f.name,onClick={format=f.name},enabled=!busy,label={Text(f.name)})}
                }
                if(format!="PNG"){Text("Качество: $quality%");Slider(quality.toFloat(),{quality=it.toInt()},valueRange=10f..100f,enabled=!busy,modifier=Modifier.testTag("batch-quality"))}
                if(format=="JPEG"){
                    Row{Checkbox(gps,{gps=it},enabled=!busy&&!exif);Text("Удалить GPS",Modifier.padding(top=12.dp))}
                    Row{Checkbox(exif,{exif=it},enabled=!busy);Text("Удалить EXIF",Modifier.padding(top=12.dp))}
                    Text("Сохраняются только основные данные камеры и даты; ориентация исправляется.")
                }else Text("PNG и WebP сохраняются без исходных метаданных.")
                Text("Фильтры")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(4.dp)){
                    PhotoEffectCategory.entries.filter{it!=PhotoEffectCategory.POPULAR&&it!=PhotoEffectCategory.FAVORITES}.forEach{c->
                        FilterChip(selected=category==c.name,onClick={category=c.name},enabled=!busy,label={Text(c.label)})
                    }
                }
                LazyRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                    items(PhotoEffectCatalog.forCategory(PhotoEffectCategory.valueOf(category)),key={it.name}){filter->
                        BatchFilterThumbnail(filter,original,renderSourceKey,strength,cache,enabled=!busy,
                            selected=effect==filter.name,onClick={effect=filter.name;compare=false})
                    }
                }
                if(effect!=PhotoEffect.NONE.name){
                    Text("Интенсивность: ${strength.toInt()}%")
                    Slider(strength,{strength=it},valueRange=0f..100f,enabled=!busy,
                        modifier=Modifier.testTag("batch-filter-strength"))
                }
                TextButton({editWatermark=true},enabled=!busy&&original!=null,modifier=Modifier.testTag("batch-edit-watermark")){
                    Text(if(watermarkLayers.isEmpty())"Добавить водяной знак" else "Изменить водяной знак")
                }
                if(watermarkLayers.isNotEmpty())TextButton({watermarkDraft="[]"},enabled=!busy){Text("Удалить водяной знак")}
                Text("Оригиналы сохраняются. Результаты — отдельные файлы.")
                if(state.total>0){
                    Text("Сохранено: ${state.outcomes.count{it.error==null}} / ${state.total}")
                    LinearProgressIndicator(progress={state.outcomes.size.toFloat()/state.total},modifier=Modifier.fillMaxWidth())
                    if(state.cancelled)Text("Обработка отменена. Готовые копии сохранены.")
                    state.failed.forEach{Text("${it.name}: ${it.error}",color=MaterialTheme.colorScheme.error)}
                    if(ready&&!busy&&state.failed.any{f->rows.any{it.id==f.id}})TextButton({onStart(rows.filter{r->state.failed.any{it.id==r.id}},spec)}){Text("Повторить ошибки")}
                }
            }
            if(state.running)Button(onCancel,Modifier.fillMaxWidth()){Text("Отменить обработку")}
            else Button({onStart(rows,spec)},Modifier.fillMaxWidth(),enabled=ready&&!busy&&rows.isNotEmpty()){Text("Обработать фото")}
        }
        if(editWatermark&&original!=null){
            val source=original
            EditorTextDialog(initial=watermarkLayers.ifEmpty{listOf(TextLayer(centerY=.9f,size=.045f,opacity=.8f))},
                loadPreview={renderBatchPreview(context,source,spec.copy(watermark="",watermarkLayers=emptyList()),640)},
                onApply={watermarkDraft=BatchWatermarkDraft.encode(it);editWatermark=false;compare=false},
                onDismiss={editWatermark=false},fitInside=true)
        }
        if(confirm)AlertDialog(onDismissRequest={confirm=false},title={Text("Обработка продолжается")},
            text={Text("Можно вернуться в галерею. Обработка продолжится в фоне.")},
            confirmButton={TextButton({confirm=false;onBack()}){Text("В галерею")}},
            dismissButton={TextButton({confirm=false}){Text("Остаться")}})
    }
}

@Composable
private fun BatchFilterThumbnail(filter:PhotoEffect,original:Bitmap?,sourceKey:String,strength:Float,
    cache:BatchPhotoPreviewCache,enabled:Boolean,selected:Boolean,onClick:()->Unit){
    val context=LocalContext.current
    var image by remember(sourceKey,filter){mutableStateOf<Bitmap?>(null)}
    LaunchedEffect(original,sourceKey,filter,strength){
        val source=original?:return@LaunchedEffect
        delay(100)
        try{image=cache.image("thumb:$sourceKey:${filter.name}:$strength"){
            renderBatchPreview(context,source,BatchPhotoSpec(maxSide=112,format=BatchPhotoFormat.PNG,
                effect=filter,effectStrength=strength),112)}}
        catch(e:CancellationException){throw e}catch(_:Exception){image=null}
    }
    Column(Modifier.width(88.dp).clickable(enabled=enabled,onClick=onClick),horizontalAlignment=Alignment.CenterHorizontally){
        Surface(shape=MaterialTheme.shapes.small,border=if(selected)androidx.compose.foundation.BorderStroke(2.dp,MaterialTheme.colorScheme.primary) else null){
            Box(Modifier.size(80.dp),contentAlignment=Alignment.Center){
                image?.let{Image(it.asImageBitmap(),filter.label,Modifier.fillMaxSize(),contentScale=ContentScale.Crop)}
            }
        }
        Text(filter.label,style=MaterialTheme.typography.labelSmall,maxLines=2)
    }
}
