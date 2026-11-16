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
internal fun CollectionCoverEditor(collection:MediaCollection?,refs:List<LibraryMediaRef>,onBack:()->Unit,
    onCreate:(suspend (String,String?,Bitmap?,CollectionCrop)->Unit)?=null,blockedIdentities:Set<String>?=emptySet(),coverAccess:CollectionCoverAccess?=null,onRename:suspend (String)->Unit) {
    val collectionId=collection?.record?.id
    val creating=collectionId==null
    val context=LocalContext.current
    val store=remember(context){CollectionCoverStore(context)}
    var name by rememberSaveable(collectionId){mutableStateOf(collection?.record?.name.orEmpty())}
    var source by rememberSaveable(collectionId){mutableStateOf<String?>(null)}
    var x by rememberSaveable(collectionId){mutableStateOf(.5f)}
    var y by rememberSaveable(collectionId){mutableStateOf(.5f)}
    var zoom by rememberSaveable(collectionId){mutableStateOf(1f)}
    var initialized by rememberSaveable(collectionId){mutableStateOf(false)}
    var automatic by rememberSaveable(collectionId){mutableStateOf(false)}
    var choosing by rememberSaveable{mutableStateOf(false)}
    var saved by remember(collectionId){mutableStateOf<CollectionCover?>(null)}
    var originalSelection by remember(collectionId){mutableStateOf<CollectionCoverSelection>(CollectionCoverSelection.Loading)}
    var decoded by remember{mutableStateOf<Pair<String,Bitmap>?>(null)}
    val fallbackAppPhotos=remember(refs,blockedIdentities){refs.asSequence().filter { it.appDownloadId!=null && it.item.mediaType==MediaType.PHOTO && !collectionRefBlocked(it,blockedIdentities.orEmpty()) }.mapNotNull{it.item.savedUri}.toSet()}
    val currentCoverAccess=coverAccess ?: CollectionCoverAccess(PhoneMediaLibrary.canReadMissingMetadata(MediaType.PHOTO,android.os.Build.VERSION.SDK_INT) { permission ->
        androidx.core.content.ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
    },fallbackAppPhotos)
    fun coverSourceAllowed(uri:String):Boolean = currentCoverAccess.permits(uri,blockedIdentities)
    val bitmap=decoded?.takeIf{it.first==source&&coverSourceAllowed(it.first)}?.second
    var error by remember{mutableStateOf<String?>(null)}
    var busy by remember{mutableStateOf(false)}
    var loading by remember{mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    DisposableEffect(decoded){val owned=decoded?.second;onDispose{owned?.recycle()}}
    val photos=remember(refs){refs.filter{it.item.mediaType==MediaType.PHOTO&&!it.item.savedUri.isNullOrBlank()&&MediaOrganizationPolicy.keys(it).isNotEmpty()}}
    var automaticCover by remember(collectionId){mutableStateOf<LibraryMediaRef?>(null)}
    LaunchedEffect(collection?.members.orEmpty(),refs){
        automaticCover=withContext(Dispatchers.Default){
            val resolved=MediaOrganizationPolicy.resolve(collection?.members.orEmpty(),refs)
            collection?.members.orEmpty().firstNotNullOfOrNull{resolved[it.id]}
        }
    }
    val liveAutomatic=automaticCover?.let{candidate->refs.firstOrNull{it.key==candidate.key&&MediaOrganizationPolicy.keys(it).isNotEmpty()&&blockedIdentities!=null&&!collectionRefBlocked(it,blockedIdentities)}}
    LaunchedEffect(collectionId){
        val selection=collectionId?.let{ id->withContext(Dispatchers.IO){store.readSelection(id)}}?:CollectionCoverSelection.Automatic
        originalSelection=selection
        val cover=(selection as? CollectionCoverSelection.Selected)?.cover
        val unavailable=selection as? CollectionCoverSelection.Unavailable
        saved=cover
        if(!initialized){
            val crop=cover?.crop?:unavailable?.crop
            source=cover?.source?:unavailable?.source;x=crop?.centerX?:.5f;y=crop?.centerY?:.5f;zoom=crop?.zoom?:1f
            automatic=selection==CollectionCoverSelection.Automatic;initialized=true
        }
    }
    val sourceAllowed=source?.let(::coverSourceAllowed)==true
    LaunchedEffect(source,blockedIdentities,sourceAllowed){
        val uri=source
        decoded=null
        if(uri==null||!coverSourceAllowed(uri)){loading=false;return@LaunchedEffect}
        loading=true
        try{decoded=uri to withContext(Dispatchers.IO){store.decode(uri)};error=null}
        catch(e:CancellationException){throw e}catch(e:Exception){decoded=null;error="Исходное изображение недоступно. Сохранённая обложка остаётся."}
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).imePadding().testTag(if(creating)"collection-create-editor" else "collection-cover-editor")) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onBack,enabled=!busy){Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Назад")};Text(if(creating)"Новая коллекция" else "Редактировать коллекцию",Modifier.weight(1f),style=MaterialTheme.typography.titleLarge,maxLines=1,overflow=TextOverflow.Ellipsis)}
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item{OutlinedTextField(name,{name=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Название")},enabled=!busy)}
            item {
                Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
                    val viewport=Modifier.widthIn(max=320.dp).fillMaxWidth().aspectRatio(.8f)
                    val image=bitmap
                    if(image!=null&&!automatic) {
                        Canvas(viewport.testTag("collection-crop-viewport").pointerInput(image,busy){
                            if(!busy)detectTransformGestures{centroid,pan,factor,_->
                                if(size.width<=0||size.height<=0)return@detectTransformGestures
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
                        val cover=if(!automatic)saved?.takeIf{coverSourceAllowed(it.source)}?.file?.let{android.net.Uri.fromFile(it).toString()} else null
                        val auto=liveAutomatic
                        if(cover!=null)DownloadThumbnail(cover,null,MediaKind.IMAGE,viewport.clickable(enabled=!busy){choosing=true})
                        else if(initialized&&automatic&&auto!=null)CollectionOriginalCover(auto,viewport.clickable(enabled=!busy){
                            val uri=auto.item.savedUri
                            if(auto.item.mediaType==MediaType.PHOTO&&!uri.isNullOrBlank()){
                                source=uri;x=.5f;y=.5f;zoom=1f;automatic=false
                            }else choosing=true
                        })
                        else Surface(viewport.clickable(enabled=!busy){choosing=true},color=MaterialTheme.colorScheme.surfaceVariant){Box(contentAlignment=Alignment.Center){Text(if(!initialized)"Загрузка обложки…" else if(automatic)"Автоматическая обложка" else "Выбранная обложка недоступна")}}
                    }
                }
            }
            item {
                if(loading)LinearProgressIndicator(Modifier.fillMaxWidth())
                if(bitmap!=null&&!automatic)Text("Кадрирование: масштаб и положение",style=MaterialTheme.typography.bodySmall)
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
        val originalSource=saved?.source?:(originalSelection as? CollectionCoverSelection.Unavailable)?.source
        Button(enabled=!busy&&!loading&&initialized&&originalSelection!=CollectionCoverSelection.Loading&&name.isNotBlank()&&(automatic||bitmap!=null||(!creating&&source==originalSource)),modifier=Modifier.fillMaxWidth().padding(16.dp),onClick={scope.launch{
            busy=true;error=null
            try {
                if(!automatic&&source!=null&&(creating||source!=originalSource||bitmap!=null))check(coverSourceAllowed(requireNotNull(source))){"Изображение недоступно"}
                if(!automatic&&source!=originalSource)check(photos.any{it.item.savedUri==source}){"Выбранное изображение больше недоступно"}
                if(creating){
                    checkNotNull(onCreate)(name,source.takeUnless{automatic},bitmap.takeUnless{automatic},CollectionCrop(x,y,zoom))
                }else{
                    val id=checkNotNull(collectionId)
                    onRename(name)
                    if(automatic)withContext(Dispatchers.IO){store.automatic(id)}
                    else bitmap?.let{image->source?.let{uri->withContext(Dispatchers.IO){store.save(id,uri,image,CollectionCrop(x,y,zoom))}}}
                    onBack()
                }
            }catch(e:CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось сохранить"}finally{busy=false}
        }}){Text(if(creating)"Создать коллекцию" else "Сохранить")}
    }
}
