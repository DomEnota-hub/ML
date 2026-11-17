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
internal fun CollectionCard(collection:MediaCollection,cover:LibraryMediaRef?,onOpen:()->Unit,modifier:Modifier=Modifier,blockedIdentities:Set<String>?=emptySet(),coverAccess:CollectionCoverAccess?=null) {
    Surface(modifier.aspectRatio(4f/5f).clickable(onClick=onOpen).testTag("collection-card-${collection.record.id}"),shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant){
        Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center){
            CollectionCoverImage(collection,cover,Modifier.fillMaxSize().testTag("collection-cover-${collection.record.id}"),blockedIdentities,coverAccess)
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha=.92f)).padding(horizontal=10.dp,vertical=7.dp)) {
                Text(collection.record.name,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}
@Composable
internal fun CollectionCoverImage(collection:MediaCollection,cover:LibraryMediaRef?,modifier:Modifier=Modifier,blockedIdentities:Set<String>?=emptySet(),coverAccess:CollectionCoverAccess?=null){
    val context=LocalContext.current
    val store=remember(context){CollectionCoverStore(context)}
    val revision by CollectionCoverStore.changes.collectAsState()
    val id=collection.record.id
    var selection by remember(id,revision){mutableStateOf(store.cachedSelection(id)?:CollectionCoverSelection.Loading)}
    LaunchedEffect(id,revision){
        // Only an explicit Automatic selection may display a member thumbnail.
        // Read failures, revoked permission and pending disk reads are placeholders.
        if(selection==CollectionCoverSelection.Loading)selection=withContext(Dispatchers.IO){store.readSelection(id)}
    }
    Box(modifier,contentAlignment=Alignment.Center){
        val access = coverAccess ?: CollectionCoverAccess(PhoneMediaLibrary.canReadMissingMetadata(com.domenota.medialoader.core.model.MediaType.PHOTO,android.os.Build.VERSION.SDK_INT) { permission ->
            androidx.core.content.ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
        }, setOfNotNull(cover?.takeIf { it.appDownloadId!=null }?.item?.savedUri))
        val selected=(selection as? CollectionCoverSelection.Selected)?.cover
        when(collectionCoverPresentation(selection,selected!=null&&access.permits(selected.source,blockedIdentities),
            cover!=null&&blockedIdentities!=null&&!collectionRefBlocked(cover,blockedIdentities))){
            CollectionCoverPresentation.CUSTOM -> key(id,selected!!.file.absolutePath){
                DownloadThumbnail(android.net.Uri.fromFile(selected.file).toString(),null,MediaKind.IMAGE,Modifier.fillMaxSize())
            }
            CollectionCoverPresentation.AUTOMATIC -> CollectionOriginalCover(requireNotNull(cover),Modifier.fillMaxSize())
            CollectionCoverPresentation.PLACEHOLDER -> Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
        }
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
internal fun CollectionGalleryStrip(collections:List<MediaCollection>,refs:List<LibraryMediaRef>,onOpen:(String?)->Unit,onCreate:()->Unit={onOpen(null)},blockedIdentities:Set<String>?=emptySet(),cachedCovers:Map<String,LibraryMediaRef> = emptyMap(),coverAccess:CollectionCoverAccess?=null,collectionsReady:Boolean=true) {
    var covers by remember(collections,refs){mutableStateOf(emptyMap<String,LibraryMediaRef>())}
    LaunchedEffect(collections,refs){covers=withContext(Dispatchers.Default){MediaOrganizationPolicy.resolve(collections.flatMap{it.members},refs)}}
    val live=remember(refs){refs.associateBy{it.key}}
    val liveCovers=remember(covers,live){covers.mapNotNull { (id,candidate) ->
        live[candidate.key]?.let { id to it }
    }.toMap()}
    Column(Modifier.fillMaxWidth().padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Text("Коллекции",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium);TextButton({onOpen(null)}){Text("Все коллекции")}}
        if(collections.isEmpty()&&collectionsReady)OrganizationEntry("Новая коллекция","Фото, видео, музыка и ссылки",onCreate)
        else if(collections.isNotEmpty()) BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width=collectionCardWidth(maxWidth.value).dp
            LazyRow(Modifier.testTag("collection-gallery-strip"),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                items(collections,key={it.record.id}){collection->
                    val cover=collectionAutomaticCover(collection.members,liveCovers,blockedIdentities,cachedCovers[collection.record.id])
                    CollectionCard(collection,cover,{onOpen(collection.record.id)},Modifier.width(width),blockedIdentities,coverAccess)
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
    val audioRevisions by com.domenota.medialoader.core.storage.AudioThumbnailInvalidation.revisions.collectAsState()
    val audioRevision=if(kind==MediaKind.AUDIO) ref.item.savedUri?.let { audioRevisions[it] } ?: 0L else 0L
    val coverIdentity=remember(ref.key,ref.item.savedUri,ref.item.mediaType,ref.item.previewUrl,ref.item.sizeBytes,ref.item.createdAtEpochMillis,ref.aliases){
        PersistentProjectionCache.digest(listOf(ref.key,ref.item.savedUri,ref.item.mediaType,ref.item.previewUrl,ref.item.sizeBytes,ref.item.createdAtEpochMillis,
            ref.aliases.sorted().joinToString("\u0000")).joinToString("\u0000"))
    }
    // A recycled LazyGrid item must not briefly replace the last authorized image
    // with the placeholder. The warm preview is keyed by the exact public identity.
    val warmKey = remember(coverIdentity,audioRevision) { "$coverIdentity:$audioRevision" }
    var bitmap by remember(ref.item.savedUri,coverIdentity,audioRevision) {
        mutableStateOf(CollectionCoverWarmBitmapCache.peek(warmKey))
    }
    LaunchedEffect(ref.item.savedUri,kind,coverIdentity,audioRevision){
        val uri=ref.item.savedUri
        val decoded=if(uri.isNullOrBlank())null else withContext(Dispatchers.IO){
            com.domenota.medialoader.ui.components.prepareDownloadThumbnail(
                context,uri,kind,ref.item.previewUrl,side=512,identityToken=coverIdentity).also { image ->
                // Scale the small retained preview off main to prevent new frame gaps.
                if(image!=null) CollectionCoverWarmBitmapCache.remember(warmKey,image)
            }
        }
        bitmap=decoded
    }
    Box(modifier,contentAlignment=Alignment.Center){
        val image=bitmap
        if(image!=null)androidx.compose.foundation.Image(image.asImageBitmap(),null,Modifier.fillMaxSize(),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
    }
}
