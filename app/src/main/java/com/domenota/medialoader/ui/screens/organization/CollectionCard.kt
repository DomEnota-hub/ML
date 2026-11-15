package com.domenota.medialoader.ui.screens.organization

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CollectionsBookmark
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CollectionCard(collection:MediaCollection,cover:LibraryMediaRef?,onOpen:()->Unit,modifier:Modifier=Modifier,blockedIdentities:Set<String>?=emptySet()) {
    Surface(modifier.aspectRatio(4f/5f).clickable(onClick=onOpen).testTag("collection-card-${collection.record.id}"),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            CollectionCoverImage(collection,cover,Modifier.fillMaxSize().testTag("collection-cover-${collection.record.id}"),blockedIdentities)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.92f)).padding(horizontal=10.dp,vertical=7.dp)) {
                Text(collection.record.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}
@Composable
internal fun CollectionCoverImage(collection:MediaCollection,cover:LibraryMediaRef?,modifier:Modifier=Modifier,blockedIdentities:Set<String>?=emptySet()){
    val context=LocalContext.current
    val store=remember(context){CollectionCoverStore(context)}
    val revision by CollectionCoverStore.changes.collectAsState()
    var saved by remember(collection.record.id){mutableStateOf<CollectionCover?>(null)}
    LaunchedEffect(collection.record.id,revision){saved=withContext(Dispatchers.IO){store.read(collection.record.id)}}
    Box(modifier,contentAlignment=Alignment.Center){
        val privateCover=saved?.takeIf{collectionCoverAllowed(it.source,blockedIdentities)}
        if(privateCover!=null)DownloadThumbnail(android.net.Uri.fromFile(privateCover.file).toString(),null,MediaKind.IMAGE,Modifier.fillMaxSize())
        else if(cover!=null&&blockedIdentities!=null&&!collectionRefBlocked(cover,blockedIdentities))CollectionOriginalCover(cover,Modifier.fillMaxSize())
        else Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun CreateCollectionCard(onOpen:()->Unit,modifier:Modifier=Modifier) {
    val border=MaterialTheme.colorScheme.outline
    Box(modifier.aspectRatio(4f/5f).clickable(onClick=onOpen).testTag("collection-create-card"),contentAlignment=Alignment.Center){
        Canvas(Modifier.matchParentSize()) {drawRoundRect(border,cornerRadius=androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),style=Stroke(1.dp.toPx(),pathEffect=PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(),6.dp.toPx()))))}
        Column(Modifier.padding(12.dp),horizontalAlignment=Alignment.CenterHorizontally){
            Icon(Icons.Rounded.Add,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
            Text("Создать коллекцию",style=MaterialTheme.typography.titleSmall)
        }
    }
}
@Composable
internal fun CollectionGalleryStrip(collections:List<MediaCollection>,refs:List<LibraryMediaRef>,onOpen:(String?)->Unit,onCreate:()->Unit={onOpen(null)},blockedIdentities:Set<String>?=emptySet()) {
    var covers by remember{mutableStateOf(emptyMap<String,LibraryMediaRef>())}
    LaunchedEffect(collections,refs){covers=withContext(Dispatchers.Default){MediaOrganizationPolicy.resolve(collections.flatMap{it.members},refs)}}
    val live=remember(refs){refs.associateBy{it.key}}
    Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Коллекции",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton({onOpen(null)}){Text("Все коллекции")}}
        if(collections.isEmpty())OrganizationEntry("Новая коллекция","Фото, видео, музыка и ссылки",onCreate)
        else BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width=collectionCardWidth(maxWidth.value).dp
            LazyRow(Modifier.testTag("collection-gallery-strip"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(collections,key={it.record.id}){collection->
                    val cover=collection.members.firstNotNullOfOrNull{covers[it.id]?.let{candidate->live[candidate.key]}}
                    CollectionCard(collection,cover,{onOpen(collection.record.id)},Modifier.width(width),blockedIdentities)
                }
                if(collections.size==1)item("create") {CreateCollectionCard(onCreate,Modifier.width(width))}
            }
        }
    }
}

@Composable
internal fun CollectionOriginalCover(ref:LibraryMediaRef,modifier:Modifier=Modifier) {
    val context=LocalContext.current
    val kind=when(ref.item.mediaType){com.domenota.medialoader.core.model.MediaType.PHOTO->MediaKind.IMAGE;com.domenota.medialoader.core.model.MediaType.VIDEO->MediaKind.VIDEO;else->MediaKind.AUDIO}
    var bitmap by remember(ref.item.savedUri){mutableStateOf<android.graphics.Bitmap?>(null)}
    LaunchedEffect(ref.item.savedUri,kind){
        val uri=ref.item.savedUri
        bitmap=if(uri.isNullOrBlank())null else withContext(Dispatchers.IO){
            com.domenota.medialoader.ui.components.prepareDownloadThumbnail(context,uri,kind,ref.item.previewUrl,side=1000)
        }
    }
    Box(modifier,contentAlignment=Alignment.Center){
        val image=bitmap
        if(image!=null)androidx.compose.foundation.Image(image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
    }
}
