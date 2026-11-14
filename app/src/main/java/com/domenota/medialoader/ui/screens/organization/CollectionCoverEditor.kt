package com.domenota.medialoader.ui.screens.organization

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CollectionCoverEditor(collection:MediaCollection,refs:List<LibraryMediaRef>,onBack:()->Unit,onRename:suspend (String)->Unit) {
    val context=LocalContext.current
    val store=remember(context){CollectionCoverStore(context)}
    var name by rememberSaveable(collection.record.id){mutableStateOf(collection.record.name)}
    var source by rememberSaveable(collection.record.id){mutableStateOf<String?>(null)}
    var x by rememberSaveable(collection.record.id){mutableStateOf(.5f)}
    var y by rememberSaveable(collection.record.id){mutableStateOf(.5f)}
    var zoom by rememberSaveable(collection.record.id){mutableStateOf(1f)}
    var initialized by rememberSaveable(collection.record.id){mutableStateOf(false)}
    var automatic by rememberSaveable(collection.record.id){mutableStateOf(false)}
    var choosing by rememberSaveable{mutableStateOf(false)}
    var saved by remember{mutableStateOf<CollectionCover?>(null)}
    var bitmap by remember{mutableStateOf<Bitmap?>(null)}
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    DisposableEffect(bitmap){val owned=bitmap;onDispose{owned?.recycle()}}
    val photos=remember(refs){refs.filter{it.item.mediaType==MediaType.PHOTO&&MediaOrganizationPolicy.keys(it).isNotEmpty()}}
    var automaticCover by remember(collection.record.id){mutableStateOf<LibraryMediaRef?>(null)}
    LaunchedEffect(collection.members,refs){
        automaticCover=withContext(Dispatchers.Default){
            val resolved=MediaOrganizationPolicy.resolve(collection.members,refs)
            collection.members.firstNotNullOfOrNull{resolved[it.id]}
        }
    }
    val liveAutomatic=automaticCover?.let{candidate->refs.firstOrNull{it.key==candidate.key&&MediaOrganizationPolicy.keys(it).isNotEmpty()}}
    LaunchedEffect(collection.record.id){
        val cover=withContext(Dispatchers.IO){store.read(collection.record.id)}
        saved=cover
        if(!initialized){source=cover?.source;x=cover?.crop?.centerX?:.5f;y=cover?.crop?.centerY?:.5f;zoom=cover?.crop?.zoom?:1f;automatic=cover==null;initialized=true}
    }
    LaunchedEffect(source){
        val uri=source
        if(uri==null){bitmap=null;return@LaunchedEffect}
        loading=true
        try{bitmap=withContext(Dispatchers.IO){store.decode(uri)};error=null}
        catch(e:CancellationException){throw e}catch(e:Exception){bitmap=null;error="Исходное изображение недоступно. Сохранённая обложка остаётся."}
        finally{loading=false}
    }
    BackHandler{if(!busy){if(choosing)choosing=false else onBack()}}
    if(choosing) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("collection-cover-picker")) {
            Row(verticalAlignment=Alignment.CenterVertically){IconButton({choosing=false}){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")};Text("Изображение обложки",style=MaterialTheme.typography.titleLarge)}
            LazyVerticalGrid(GridCells.Fixed(3),contentPadding=PaddingValues(8.dp),horizontalArrangement=Arrangement.spacedBy(3.dp),verticalArrangement=Arrangement.spacedBy(3.dp)){
                items(photos,key={it.key}){ref->DownloadThumbnail(ref.item.savedUri,ref.item.previewUrl,MediaKind.IMAGE,
                    Modifier.fillMaxWidth().aspectRatio(1f).clickable{source=ref.item.savedUri;x=.5f;y=.5f;zoom=1f;automatic=false;choosing=false})}
                if(photos.isEmpty())item(span={GridItemSpan(3)}){Text("Нет доступных изображений",Modifier.padding(20.dp))}
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("collection-cover-editor")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onBack,enabled=!busy){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")};Text("Редактировать коллекцию",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)}
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Название")},enabled=!busy)}
            item {
                BoxWithConstraints(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                    val viewport=Modifier.widthIn(max=320.dp).fillMaxWidth().aspectRatio(.8f)
                    val image=bitmap
                    if(image!=null&&!automatic) {
                        Canvas(viewport.testTag("collection-crop-viewport").pointerInput(image,busy){
                            if(!busy)detectTransformGestures{centroid,pan,factor,_->
                                val old=CollectionCrop(x,y,zoom).bounded(image.width,image.height)
                                val rect=old.rect(image.width,image.height)
                                val nextZoom=(old.zoom*factor).coerceIn(1f,8f)
                                val actual=nextZoom/old.zoom
                                val focalX=(centroid.x/size.width-.5f)*rect.width/image.width
                                val focalY=(centroid.y/size.height-.5f)*rect.height/image.height
                                val next=CollectionCrop(old.centerX+focalX-focalX/actual-pan.x/size.width*rect.width/image.width,
                                    old.centerY+focalY-focalY/actual-pan.y/size.height*rect.height/image.height,nextZoom).bounded(image.width,image.height)
                                x=next.centerX;y=next.centerY;zoom=next.zoom
                            }
                        }) {
                            val rect=CollectionCrop(x,y,zoom).rect(image.width,image.height)
                            drawContext.canvas.nativeCanvas.drawBitmap(image,Rect(rect.left.toInt(),rect.top.toInt(),(rect.left+rect.width).toInt(),(rect.top+rect.height).toInt()),RectF(0f,0f,size.width,size.height),Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                            for(i in 1..2){drawLine(Color.White.copy(alpha=.65f),Offset(size.width*i/3,0f),Offset(size.width*i/3,size.height),1.dp.toPx());drawLine(Color.White.copy(alpha=.65f),Offset(0f,size.height*i/3),Offset(size.width,size.height*i/3),1.dp.toPx())}
                        }
                    } else {
                        val cover=if(!automatic)saved?.file?.let{android.net.Uri.fromFile(it).toString()} else null
                        val auto=liveAutomatic
                        if(cover!=null)DownloadThumbnail(cover,null,MediaKind.IMAGE,viewport)
                        else if(auto!=null)DownloadThumbnail(auto.item.savedUri,auto.item.previewUrl,when(auto.item.mediaType){MediaType.PHOTO->MediaKind.IMAGE;MediaType.VIDEO->MediaKind.VIDEO;else->MediaKind.AUDIO},viewport)
                        else Surface(viewport,color=MaterialTheme.colorScheme.surfaceVariant){Box(contentAlignment=Alignment.Center){Text("Автоматическая обложка")}}
                    }
                }
            }
            item {
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(bitmap!=null&&!automatic)Text("Масштаб и положение",style=MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({choosing=true},enabled=!busy,modifier=Modifier.weight(1f)){Text("Выбрать изображение")}
                    OutlinedButton({x=.5f;y=.5f;zoom=1f},enabled=!busy&&bitmap!=null&&!automatic){Text("Автоподгон")}
                }
                Row {
                    TextButton({automatic=true;source=null;x=.5f;y=.5f;zoom=1f},enabled=!busy){Text("Автоматическая обложка")}
                    TextButton({val initial=saved?.takeIf{it.source==source}?.crop?:CollectionCrop();x=initial.centerX;y=initial.centerY;zoom=initial.zoom},enabled=!busy&&bitmap!=null&&!automatic){Text("Сбросить")}
                }
                error?.let{Text(it,color=MaterialTheme.colorScheme.error)}
            }
        }
        Button(enabled=!busy&&!loading&&initialized&&name.isNotBlank(),modifier=Modifier.fillMaxWidth().padding(16.dp),onClick={scope.launch{
            busy=true;error=null
            try {
                if(!automatic&&source!=saved?.source)check(photos.any{it.item.savedUri==source}){"Выбранное изображение больше недоступно"}
                onRename(name)
                if(automatic)withContext(Dispatchers.IO){store.automatic(collection.record.id)}
                else bitmap?.let{image->source?.let{uri->withContext(Dispatchers.IO){store.save(collection.record.id,uri,image,CollectionCrop(x,y,zoom))}}}
                onBack()
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось сохранить"}finally{busy=false}
        }}){Text("Сохранить")}
    }
}
