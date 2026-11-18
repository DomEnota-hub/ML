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
import com.domenota.medialoader.ui.model.MediaKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive

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
internal fun CollectionCoverImage(collection:MediaCollection,cover:LibraryMediaRef?,modifier:Modifier=Modifier,blockedIdentities:Set<String>?=emptySet(),coverAccess:CollectionCoverAccess?=null,decoder:CollectionOriginalCoverDecoder=DefaultCollectionOriginalCoverDecoder){
    val context=LocalContext.current
    val store=remember(context){CollectionCoverStore(context)}
    val snapshots=remember(context){LastCollectionCoverStore(context)}
    val revision by CollectionCoverStore.changes.collectAsState()
    val id=collection.record.id
    // Revision checks never reset a rendered image to Loading.
    var selection by remember(id){mutableStateOf(store.cachedSelection(id)?:CollectionCoverSelection.Loading)}
    var last by remember(id){mutableStateOf(snapshots.peek(id))}
    var diskReady by remember(id){mutableStateOf(last!=null)}
    var removedSlot by remember(id){mutableStateOf<String?>(null)}
    val access = coverAccess ?: CollectionCoverAccess(PhoneMediaLibrary.canReadMissingMetadata(com.domenota.medialoader.core.model.MediaType.PHOTO,android.os.Build.VERSION.SDK_INT) { permission ->
        androidx.core.content.ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
    }, setOfNotNull(cover?.takeIf { it.appDownloadId!=null }?.item?.savedUri))
    // ML privacy and Android source permission are separate inputs. Missing phone
    // permission cannot revoke the display copy; unknown ML privacy always can.
    val privacy=remember(coverAccess!=null,access.snapshotPrivacy,blockedIdentities){
        // Explicit unknown authorization cannot fall back to a stale public set.
        if(coverAccess==null) blockedIdentities else access.snapshotPrivacy?.let { known ->
            if(blockedIdentities==null || blockedIdentities===known) known else known+blockedIdentities
        }
    }
    val selected=(selection as? CollectionCoverSelection.Selected)?.cover
    val member=collectionAutomaticCoverMember(collection.members,privacy)
    val automaticSlot=remember(member?.id,member?.identityJson){member?.takeIf {
        MediaOrganizationPolicy.decode(it.identityJson).isNotEmpty()
    }?.let { "automatic:"+PersistentProjectionCache.digest(it.id+"\u0000"+it.identityJson) }}
    val slot=remember(selected,selection,automaticSlot){when {
        selected!=null -> "custom:"+PersistentProjectionCache.digest(selected.file.absolutePath+selected.source+selected.crop)
        selection==CollectionCoverSelection.Automatic -> automaticSlot
        else -> null
    }}
    val audioRevisions by com.domenota.medialoader.core.storage.AudioThumbnailInvalidation.revisions.collectAsState()
    val contentToken=remember(selected,slot,cover,audioRevisions[cover?.item?.savedUri]){if(selected!=null)slot else cover?.let {
        PersistentProjectionCache.digest(listOf(it.key,it.item.savedUri,it.item.mediaType,it.item.previewUrl,it.item.sizeBytes,
            it.item.createdAtEpochMillis,it.aliases.sorted(),audioRevisions[it.item.savedUri]?:0L).joinToString("\u0000"))
    }}
    val currentPrivacy by rememberUpdatedState(privacy)
    val currentSlot by rememberUpdatedState(slot)
    val currentSource by rememberUpdatedState(selected?.source?:cover?.item?.savedUri)
    val currentAccess by rememberUpdatedState(access)
    LaunchedEffect(id,revision){
        val next=withContext(Dispatchers.IO){store.readSelection(id)}
        selection=next
    }
    LaunchedEffect(id){
        val observed=last
        val observedRemoved=removedSlot
        val loaded=withContext(Dispatchers.IO){snapshots.read(id)}
        // A slower hydration cannot overwrite a newer accepted frame or tombstone.
        if(last===observed && removedSlot==observedRemoved)
            last=snapshots.peek(id)?:loaded
        diskReady=true
    }
    LaunchedEffect(id,slot,contentToken,privacy,revision,access,decoder){
        if(slot==null || contentToken==null || privacy==null)return@LaunchedEffect
        val previous=last
        if(access.sourceRevision==0L && previous?.metadata?.let{it.slotToken==slot && it.contentToken==contentToken && lastCollectionCoverAllowed(it,slot,privacy)}==true)
            return@LaunchedEffect
        var confirmedRemoval=false
        val frame=withContext(Dispatchers.IO){
            try {
                val source:String
                val identities:Set<String>
                val ref=if(selected==null)cover else null
                if(selected!=null){
                    if(!collectionCoverAllowed(selected.source,privacy))return@withContext null
                    source=selected.source
                    identities=selected.identities+com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(source)
                }else{
                    if(ref==null || collectionRefBlocked(ref,privacy) || member==null ||
                        !CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson),ref))return@withContext null
                    val canRead=ref.appDownloadId!=null || PhoneMediaLibrary.canReadMissingMetadata(ref.item.mediaType,android.os.Build.VERSION.SDK_INT) { permission ->
                        androidx.core.content.ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
                    }
                    if(!canRead)return@withContext null
                    source=ref.item.savedUri?:return@withContext null
                    identities=MediaOrganizationPolicy.keys(ref)+ref.aliases+ref.key+ref.item.id+
                        listOfNotNull(ref.appDownloadId)+com.domenota.medialoader.data.playback.PublicMediaIdentity.uriKeys(source)+collectionCoverVolumeKeys(context,source)
                }
                val committed=snapshots.read(id)
                val check=if(selected!=null)CollectionCoverSourceCheck(contentToken) else
                    collectionCoverSourceCheck(context,source,contentToken,access,committed?.metadata)
                if(check.confirmedAbsent){
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(currentSlot==slot && currentSource==source && currentAccess==access &&
                        lastCollectionCoverAllowed(requireNotNull(committed).metadata,currentSlot,currentPrivacy)){
                        snapshots.remove(id)
                        confirmedRemoval=true
                    }
                    return@withContext null
                }
                // A failed provider check cannot replace an already accepted
                // frame with an unverified fallback generation.
                if(check.token==null && committed?.metadata?.let{it.slotToken==slot && it.source==source}==true)return@withContext committed
                val verifiedToken=check.token?:contentToken
                val metadata=LastCollectionCover(slot,verifiedToken,source,identities,System.currentTimeMillis())
                if(committed?.metadata?.let{it.slotToken==slot && it.contentToken==verifiedToken && it.identities==identities}==true)
                    return@withContext committed
                val image=if(selected!=null)android.graphics.BitmapFactory.decodeFile(selected.file.absolutePath) else {
                    val original=requireNotNull(ref)
                    val kind=when(original.item.mediaType){com.domenota.medialoader.core.model.MediaType.PHOTO->MediaKind.IMAGE;com.domenota.medialoader.core.model.MediaType.VIDEO->MediaKind.VIDEO;else->MediaKind.AUDIO}
                    decoder.decode(context,source,kind,original.item.previewUrl,verifiedToken)
                }?:return@withContext null
                try {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    if(!lastCollectionCoverAllowed(metadata,currentSlot,currentPrivacy))return@withContext null
                    snapshots.save(id,metadata,image)
                    snapshots.peek(id)
                }finally{if(selected!=null)image.recycle()}
            }catch(cancelled:kotlinx.coroutines.CancellationException){throw cancelled}
            catch(_:Exception){null} // An unavailable source is never a deletion.
        }
        if(confirmedRemoval){removedSlot=slot;last=null} else if(frame!=null){removedSlot=null;last=frame}
    }
    // A removed provider row may no longer resolve to a LibraryMediaRef. Probe
    // the last known source separately; lack of a ref itself never clears it.
    LaunchedEffect(id,slot,cover==null,access,privacy){
        if(cover!=null || selected!=null || slot==null || privacy==null)return@LaunchedEffect
        val removed=withContext(Dispatchers.IO){
            val committed=snapshots.read(id)?:return@withContext false
            if(!lastCollectionCoverAllowed(committed.metadata,slot,privacy))return@withContext false
            val check=collectionCoverSourceCheck(context,committed.metadata.source,committed.metadata.contentToken,access,committed.metadata)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if(check.confirmedAbsent && currentSlot==slot && currentSource==null && currentAccess==access &&
                lastCollectionCoverAllowed(committed.metadata,currentSlot,currentPrivacy) &&
                snapshots.peek(id)?.metadata==committed.metadata){
                snapshots.remove(id);true
            }else false
        }
        if(removed){removedSlot=slot;last=null}
    }
    Box(modifier.testTag(if(selected!=null) "collection-cover-custom-slot" else "collection-cover-automatic-slot"),contentAlignment=Alignment.Center){
        val automaticSafe=selected!=null || cover==null || (privacy!=null && member!=null &&
            cover.item.state==com.domenota.medialoader.core.model.DownloadState.COMPLETED &&
            !collectionRefBlocked(cover,privacy) && CollectionAddressResolver.matches(MediaOrganizationPolicy.decode(member.identityJson),cover))
        val frame=last?.takeIf{ cached -> slot!=removedSlot && automaticSafe && (
            lastCollectionCoverAllowed(cached.metadata,slot,privacy) ||
                (cached.metadata.slotToken.startsWith("custom:") &&
                    ((selected!=null && collectionCoverAllowed(selected.source,privacy) && selected.identities.none{it in privacy.orEmpty()}) ||
                        (selection==CollectionCoverSelection.Automatic && slot!=null)) &&
                    lastCollectionCoverAllowed(cached.metadata,cached.metadata.slotToken,privacy)) ||
                (selected!=null && privacy!=null && cached.metadata.slotToken==automaticSlot &&
                    (cover==null || !collectionRefBlocked(cover,privacy)) &&
                    collectionCoverAllowed(selected.source,privacy) && selected.identities.none{it in privacy.orEmpty()} &&
                    lastCollectionCoverAllowed(cached.metadata,cached.metadata.slotToken,privacy)))
        }
        if(frame!=null)androidx.compose.foundation.Image(frame.bitmap.asImageBitmap(),null,Modifier.fillMaxSize().testTag("collection-last-cover-image"),
            contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        // While the private disk copy is being hydrated, don't flash a yellow
        // placeholder. A failed refresh leaves the prior accepted frame intact.
        else if(diskReady && privacy!=null && selection!=CollectionCoverSelection.Loading)
            Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp),tint=MaterialTheme.colorScheme.primary)
    }
}

private data class CollectionCoverSourceCheck(val token:String?,val confirmedAbsent:Boolean=false)

private fun collectionCoverVolumeKeys(context:android.content.Context,source:String):Set<String> = try {
    if(android.os.Build.VERSION.SDK_INT>=30 && android.net.Uri.parse(source).authority=="media")
        android.provider.MediaStore.getExternalVolumeNames(context).map{"cover-volume:$it"}.toSet() else emptySet()
}catch(_:Exception){emptySet()}

/** A successful empty MediaStore cursor alone is insufficient under scoped/partial
 * access or an ejected volume. Only full photo access, included pending/trash rows
 * and the same mounted volume set captured with the old frame prove absence.
 * Other authorities/file URIs are deliberately never classified as deleted here. */
private fun collectionCoverSourceCheck(context:android.content.Context,source:String,fallback:String,
    access:CollectionCoverAccess,old:LastCollectionCover?):CollectionCoverSourceCheck {
    val uri=android.net.Uri.parse(source)
    if(android.os.Build.VERSION.SDK_INT<30 || uri.authority!="media")return CollectionCoverSourceCheck(fallback)
    fun fullRead()=access.fullPhotoAccess && PhoneMediaLibrary.canReadMissingMetadata(
        com.domenota.medialoader.core.model.MediaType.PHOTO,android.os.Build.VERSION.SDK_INT) { permission ->
        androidx.core.content.ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val segments=uri.pathSegments
    val photoItem=segments.size==4 && segments[0] in setOf("external","external_primary") &&
        segments[1]=="images" && segments[2]=="media" && segments[3].toLongOrNull()!=null
    val initialVolumes=collectionCoverVolumeKeys(context,source)
    fun mounted()=android.os.Environment.getExternalStorageState()==android.os.Environment.MEDIA_MOUNTED &&
        initialVolumes.isNotEmpty() && initialVolumes==collectionCoverVolumeKeys(context,source) &&
        old?.identities?.filter{it.startsWith("cover-volume:")}?.toSet()==initialVolumes
    val permittedBefore=fullRead()
    return try {
        val args=android.os.Bundle().apply {
            putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED,android.provider.MediaStore.MATCH_INCLUDE)
            putInt(android.provider.MediaStore.QUERY_ARG_MATCH_PENDING,android.provider.MediaStore.MATCH_INCLUDE)
        }
        context.contentResolver.query(uri,arrayOf(android.provider.MediaStore.MediaColumns.SIZE,
            android.provider.MediaStore.MediaColumns.DATE_MODIFIED,android.provider.MediaStore.MediaColumns.IS_TRASHED,
            android.provider.MediaStore.MediaColumns.IS_PENDING),args,null)?.use { cursor ->
            val present=cursor.moveToFirst()
            val trashed=present && cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.IS_TRASHED).let{it>=0 && cursor.getInt(it)==1}
            if(!present || trashed){
                CollectionCoverSourceCheck(null,photoItem && old!=null && old.source==source && permittedBefore && fullRead() && mounted())
            }else{
                val size=cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.SIZE)
                val modified=cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATE_MODIFIED)
                val pending=cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.IS_PENDING)
                if(size<0 || modified<0 || cursor.isNull(size) || cursor.isNull(modified) ||
                    (pending>=0 && cursor.getInt(pending)==1))CollectionCoverSourceCheck(null)
                else CollectionCoverSourceCheck(PersistentProjectionCache.digest(fallback+":"+cursor.getLong(size)+":"+cursor.getLong(modified)))
            }
        }?:CollectionCoverSourceCheck(null)
    }catch(_:Exception){CollectionCoverSourceCheck(null)}
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
/** Previous cover selection may be reused during an asynchronous rebind
 * only if the same collection member still resolves to the exact current,
 * public physical identity. This prevents a full placeholder frame on a
 * routine gallery recomposition without leaking a retired/private image. */
internal data class CollectionCoverCandidate(
    val memberIdentity: String,
    val ref: LibraryMediaRef,
)

internal fun reusableCollectionCovers(
    previous: Map<String, CollectionCoverCandidate>,
    memberIdentities: Map<String, String>,
    currentRefs: List<LibraryMediaRef>,
): Map<String, LibraryMediaRef> {
    val live = currentRefs.associateBy { it.key }
    return previous.mapNotNull { (memberId, cached) ->
        if (memberIdentities[memberId] != cached.memberIdentity) return@mapNotNull null
        val current = live[cached.ref.key] ?: return@mapNotNull null
        val a = cached.ref
        val b = current
        if (a.item.savedUri != b.item.savedUri ||
            a.item.sizeBytes != b.item.sizeBytes ||
            a.item.createdAtEpochMillis != b.item.createdAtEpochMillis ||
            a.item.mediaType != b.item.mediaType ||
            a.aliases != b.aliases ||
            b.item.hidden || b.item.isPrivate ||
            b.item.state != com.domenota.medialoader.core.model.DownloadState.COMPLETED)
            null else memberId to b
    }.toMap()
}

@Composable
internal fun CollectionGalleryStrip(collections:List<MediaCollection>,refs:List<LibraryMediaRef>,onOpen:(String?)->Unit,onCreate:()->Unit={onOpen(null)},blockedIdentities:Set<String>?=emptySet(),cachedCovers:Map<String,LibraryMediaRef> = emptyMap(),coverAccess:CollectionCoverAccess?=null,collectionsReady:Boolean=true) {
    var covers by remember { mutableStateOf<Map<String,CollectionCoverCandidate>>(emptyMap()) }
    val memberIdentities=remember(collections){
        collections.flatMap { it.members }.mapNotNull { member ->
            member.identityJson?.let { member.id to it }
        }.toMap()
    }
    LaunchedEffect(collections,refs) {
        val resolved=withContext(Dispatchers.Default){
            MediaOrganizationPolicy.resolve(collections.flatMap{it.members},refs)
        }
        covers=resolved.mapNotNull { (memberId,ref) ->
            memberIdentities[memberId]?.let { memberId to CollectionCoverCandidate(it,ref) }
        }.toMap()
    }
    val liveCovers=remember(covers,memberIdentities,refs) {
        reusableCollectionCovers(covers,memberIdentities,refs)
    }
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

/** The production decoder remains unchanged. Injection makes lifecycle/compose
 * regression tests deterministic without relying on Robolectric's incomplete
 * native JPEG decoder or a real file/content resolver. */
internal fun interface CollectionOriginalCoverDecoder {
    suspend fun decode(
        context: android.content.Context,
        uri: String,
        kind: MediaKind,
        previewUrl: String?,
        identity: String,
    ): android.graphics.Bitmap?
}

private object DefaultCollectionOriginalCoverDecoder : CollectionOriginalCoverDecoder {
    override suspend fun decode(context: android.content.Context,uri:String,
        kind:MediaKind,previewUrl:String?,identity:String):android.graphics.Bitmap? =
        com.domenota.medialoader.ui.components.prepareDownloadThumbnail(
            context,uri,kind,previewUrl,side=512,identityToken=identity)
}

@Composable
internal fun CollectionOriginalCover(
    ref:LibraryMediaRef,
    modifier:Modifier=Modifier,
    decoder:CollectionOriginalCoverDecoder=DefaultCollectionOriginalCoverDecoder,
) {
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
    LaunchedEffect(ref.item.savedUri,kind,coverIdentity,audioRevision,decoder){
        val uri=ref.item.savedUri
        val decoded=if(uri.isNullOrBlank())null else withContext(Dispatchers.IO){
            decoder.decode(context,uri,kind,ref.item.previewUrl,coverIdentity).also { image ->
                // Scale the small retained preview off main to prevent new frame gaps.
                if(image!=null) CollectionCoverWarmBitmapCache.remember(warmKey,image)
            }
        }
        bitmap=decoded
    }
    Box(modifier,contentAlignment=Alignment.Center){
        val image=bitmap
        if(image!=null)androidx.compose.foundation.Image(image.asImageBitmap(),null,
            Modifier.fillMaxSize().testTag("collection-original-cover-image"),contentScale=androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(Icons.Rounded.CollectionsBookmark,null,Modifier.size(42.dp).testTag("collection-original-cover-placeholder"),
            tint=MaterialTheme.colorScheme.primary)
    }
}
