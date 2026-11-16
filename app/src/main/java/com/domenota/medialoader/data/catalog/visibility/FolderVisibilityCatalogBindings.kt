package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import androidx.room.withTransaction
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.library.ExternalMediaEntry
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.ExternalMediaCatalogRepository
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.MediaOrganizationPolicy
import com.domenota.medialoader.data.playback.ListeningHistoryPolicy
import com.domenota.medialoader.data.playback.ListeningHistoryStore
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import com.domenota.medialoader.data.storage.HistoricalReferenceLinks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A single-member cross-store binding intent is durable before the first CAS.
 * The caller must keep ordinary mutations and playback paused until batch commit.
 * External files keep their external key, never acquire an invented download row. */
internal class FolderVisibilityCatalogBindings(
    context: Context,
    private val database: HistoryDatabase,
    private val external: ExternalMediaCatalogRepository = ExternalMediaCatalogRepository(context),
) : VisibilityCatalogBindings {
    private val context = context.applicationContext
    private val links = HistoricalReferenceLinks(this.context,database)
    private val collections = VisibilityCollectionLinks(database)
    private val listening = ListeningHistoryStore.get(this.context)
    private val dao = database.downloadDao()
    private val catalog = database.catalogDao()
    private val proofs = VisibilityBindingProofStore(java.io.File(this.context.noBackupFilesDir,"folder-visibility-bindings-v1"))

    private data class Member(val ref: LibraryMediaRef, val folder: String?, val favorite: Boolean)
    private var activeBatch: VisibilityBatchRecord? = null
    private var permitSeed = false
    fun batchContext(batch: VisibilityBatchRecord?, preflight:Boolean=false) { activeBatch=batch; permitSeed=preflight }

    private suspend fun currentMember(id:String):Member? {
        val app=dao.byId(id)
        val ext=external.visibilityEntries().filter { it.ref.key==id }
        check(ext.size<=1 && (app==null || ext.isEmpty())) { "Неоднозначная запись каталога" }
        return if(app!=null) {
            val assignment=catalog.assignmentById(id)
            Member(LibraryMediaRef(id,emptySet(),app,id),assignment?.folderId,assignment?.isFavorite==true)
        } else ext.singleOrNull()?.let { Member(it.ref,it.folderId,it.isFavorite) }
    }
    private suspend fun member(id:String,folder:String):Member {
        val change=activeBatch?.takeIf { it.folderId==folder }?.membership?.takeIf { it.id==id }
        val result=currentMember(id) ?: change?.takeIf { permitSeed && !it.app && it.sourceFolder==null }?.externalSeed?.let {
            val seed=external.decodeVisibilitySeed(it); Member(seed.ref,seed.folderId,seed.isFavorite)
        } ?: error("Не найдена точная запись каталога")
        check(if(change==null) result.folder==folder else result.folder in setOf(change.sourceFolder,change.targetFolder) &&
            (result.ref.appDownloadId!=null)==change.app && result.favorite==change.favorite) { "Состав папки изменился" }
        requirePublic(result.ref.item)
        return result
    }
    private fun requirePublic(row:DownloadEntity) {
        check(row.state==DownloadState.COMPLETED && !row.isPrivate && !row.hidden && !row.savedUri.isNullOrBlank()) { "Файл недоступен или защищён" }
    }

    /** Construct the immutable plan before any copy, after exact membership and
     * typed MediaStore identity are proven. All data comes from the current row. */
    @androidx.annotation.RequiresApi(30)
    suspend fun plan(folderId: String, direction: VisibilityDirection, state: CatalogVisibilityState,
        media: VisibilityMediaStore, onPlanning: (Int,Int) -> Unit = {_,_->}): VisibilityBatchRecord = withContext(Dispatchers.IO) {
        check(catalog.folderById(folderId) != null)
        val app = catalog.memberIds(folderId).toSet()
        val phone = externalMemberIds(folderId)
        check(app.intersect(phone).isEmpty()) { "Неоднозначный состав папки" }
        val ids = (app + phone).sorted()
        check(ids.size <= VisibilityBatchRecord.MAX_MEMBERS)
        onPlanning(0,ids.size)
        val members = ids.mapIndexed { index,id ->
            planRecord(id,folderId,direction,member(id,folderId).ref.item,state,media).also { onPlanning(index+1,ids.size) }
        }
        VisibilityBatchRecord(java.util.UUID.randomUUID().toString(),folderId,direction,members)
    }
    @androidx.annotation.RequiresApi(30)
    private suspend fun planRecord(id:String,folder:String,direction:VisibilityDirection,current:DownloadEntity,
        state:CatalogVisibilityState,media:VisibilityMediaStore):VisibilityMoveRecord {
        requirePublic(current)
        val uri=requireNotNull(current.savedUri)
        val operation=java.util.UUID.randomUUID().toString(); val token=java.util.UUID.randomUUID().toString()
        return if(direction==VisibilityDirection.HIDE) {
            val mime=when(current.mediaType) { MediaType.PHOTO->"image/*"; MediaType.VIDEO->"video/*"; MediaType.AUDIO->"audio/*" }
            val origin=media.captureCatalog(uri,mime)
            val fingerprint=HiddenFolderFileStore.fingerprint(media.openOriginal(origin))
            check(fingerprint.bytes==origin.bytes && current.sizeBytes==origin.bytes) { "Размер файла изменился" }
            VisibilityMoveRecord(operation,folder,id,direction,origin.uri,origin.name,origin.modifiedMillis,
                fingerprint,token,publicOrigin=origin,catalogSourceUri=uri.takeUnless { it==origin.uri })
        } else {
            val previous=checkNotNull(state.receipt(id)) { "Не найдено исходное расположение файла" }
            val origin=checkNotNull(previous.publicOrigin)
            check(previous.direction==VisibilityDirection.HIDE && previous.folderId==folder && previous.destinationUri==uri)
            val parsed=android.net.Uri.parse(uri)
            check(parsed.scheme=="file" && parsed.authority.isNullOrEmpty() && parsed.query==null && parsed.fragment==null)
            val file=java.io.File(requireNotNull(parsed.path)); val store=FolderVisibilityStorageAccess.store()
            store.verifyLocation(file,origin.modifiedMillis)
            val fingerprint=HiddenFolderFileStore.fingerprint(file.inputStream())
            check(fingerprint==previous.fingerprint && current.sizeBytes==fingerprint.bytes) { "Содержимое скрытого файла изменилось" }
            VisibilityMoveRecord(operation,folder,id,direction,uri,origin.name,origin.modifiedMillis,fingerprint,token,publicOrigin=origin)
        }
    }

    /** Legacy v1 omitted placement metadata. Recover it only while this exact
     * journaled public source still exists and matches its immutable full hash. */
    @androidx.annotation.RequiresApi(30)
    suspend fun enrichLegacy(record: VisibilityMoveRecord, media: VisibilityMediaStore): VisibilityMoveRecord = withContext(Dispatchers.IO) {
        if (record.publicOrigin != null) return@withContext record
        check(record.direction == VisibilityDirection.HIDE) {
            "В старом журнале показа нет исходного расположения; журналы сохранены"
        }
        val current = checkNotNull(currentMember(record.downloadId)) {
            "Не найдена точная запись старого переноса; журналы сохранены"
        }
        requirePublic(current.ref.item)
        check(current.folder == record.folderId && current.ref.item.sizeBytes == record.fingerprint.bytes &&
            current.ref.item.savedUri in setOfNotNull(record.logicalSourceUri, record.destinationUri)) {
            "Запись старого переноса изменилась; журналы сохранены"
        }
        val mime = when (current.ref.item.mediaType) {
            MediaType.PHOTO -> "image/*"; MediaType.VIDEO -> "video/*"; MediaType.AUDIO -> "audio/*"
        }
        // Never search by filename or substitute the current catalog locator.
        val origin = try { media.captureCatalog(record.sourceUri, mime) }
        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (failure: Exception) {
            throw IllegalStateException("Не удалось точно подтвердить исходник старого переноса; журналы сохранены", failure)
        }
        check(origin.name == record.sourceName && origin.bytes == record.fingerprint.bytes &&
            (record.sourceModifiedMillis == null || origin.modifiedMillis == record.sourceModifiedMillis) &&
            HiddenFolderFileStore.fingerprint(media.openOriginal(origin)) == record.fingerprint) {
            "Исходник старого переноса не совпал по имени, дате или SHA-256; журналы сохранены"
        }
        check(currentMember(record.downloadId) == current) {
            "Запись старого переноса изменилась во время проверки; журналы сохранены"
        }
        check(record.sourceUri == origin.uri || record.sourceUri == record.logicalSourceUri) {
            "В старом журнале недостаточно точных адресов исходника; журналы сохранены"
        }
        record.copy(sourceUri = origin.uri, sourceModifiedMillis = origin.modifiedMillis, publicOrigin = origin,
            catalogSourceUri = record.logicalSourceUri.takeUnless { it == origin.uri })
    }

    suspend fun memberFolder(ref:LibraryMediaRef):String? = withContext(Dispatchers.IO) {
        if(ref.appDownloadId!=null) {
            val current=checkNotNull(currentMember(ref.appDownloadId)); check(current.ref.item.savedUri==ref.item.savedUri)
            current.folder
        } else {
            val matches=external.visibilityEntries().filter { it.ref.key==ref.key || it.ref.item.savedUri==ref.item.savedUri }
            check(matches.size<=1 && matches.all { it.ref.item.savedUri==ref.item.savedUri }) { "Неоднозначная запись внешнего каталога" }
            matches.singleOrNull()?.folderId
        }
    }

    /** Membership changes are themselves a batch intent, so a process death
     * between assignment and physical copy cannot leave a silently exposed file. */
    @androidx.annotation.RequiresApi(30)
    suspend fun planMembership(ref:LibraryMediaRef,target:String?,state:CatalogVisibilityState,
        media:VisibilityMediaStore):VisibilityBatchRecord? = withContext(Dispatchers.IO) {
        requirePublic(ref.item)
        check(target==null || catalog.folderById(target)!=null) { "Папка не найдена" }
        val app=ref.appDownloadId
        val uri=requireNotNull(ref.item.savedUri)
        val selected = if(app!=null) checkNotNull(currentMember(app))
            else {
                val all=external.visibilityEntries()
                val matches=all.filter { it.ref.key==ref.key || it.ref.item.savedUri==uri }
                check(matches.size<=1 && matches.all { it.ref.item.savedUri==uri }) { "Неоднозначная запись внешнего каталога" }
                matches.singleOrNull()?.let { Member(it.ref,it.folderId,it.isFavorite) } ?: Member(ref,null,false)
            }
        val id=selected.ref.key; val from=selected.folder
        check(selected.ref.item.savedUri==uri && selected.ref.item.sizeBytes==ref.item.sizeBytes &&
            (selected.ref.appDownloadId!=null)==(app!=null)) { "Выбранный файл изменился" }
        if(from==target) return@withContext null
        val sourceHidden=from?.let { state.isHidden(it) }==true
        val targetHidden=target?.let { state.isHidden(it) }==true
        if(!sourceHidden && !targetHidden) return@withContext null
        val previous=state.receipt(id)
        val seed=if(app!=null) null else external.encodeVisibilitySeed(ExternalMediaEntry(selected.ref,from,selected.favorite,true))
        val change=VisibilityMemberChange(id,app!=null,from,target,uri,selected.favorite,seed,previous)
        val operation=java.util.UUID.randomUUID().toString()
        if(sourceHidden && targetHidden) {
            check(previous?.direction==VisibilityDirection.HIDE && previous.folderId==from && previous.destinationUri==uri)
            verifyHiddenMember(requireNotNull(previous),selected.ref.item)
            VisibilityBatchRecord(operation,requireNotNull(from),VisibilityDirection.HIDE,emptyList(),membership=change)
        } else {
            val direction=if(targetHidden) VisibilityDirection.HIDE else VisibilityDirection.SHOW
            val folder=requireNotNull(if(targetHidden) target else from)
            val record=planRecord(id,folder,direction,selected.ref.item,state,media)
            VisibilityBatchRecord(operation,folder,direction,listOf(record),membership=change)
        }
    }
    private suspend fun verifyHiddenMember(receipt:VisibilityMoveRecord,row:DownloadEntity) {
        check(row.savedUri==receipt.destinationUri && row.sizeBytes==receipt.fingerprint.bytes)
        val file=java.io.File(requireNotNull(android.net.Uri.parse(requireNotNull(row.savedUri)).path))
        check(FolderVisibilityStorageAccess.store().matchesReceipt(file,receipt) &&
            HiddenFolderFileStore.fingerprint(file.inputStream())==receipt.fingerprint) { "Скрытый файл изменился" }
    }
    suspend fun verifyMembershipIntent(batch:VisibilityBatchRecord,state:CatalogVisibilityState,initial:Boolean) {
        val change=checkNotNull(batch.membership)
        for(folder in listOfNotNull(change.sourceFolder,change.targetFolder)) check(catalog.folderById(folder)!=null)
        val fromHidden=change.sourceFolder?.let { state.isHidden(it) }==true
        val toHidden=change.targetFolder?.let { state.isHidden(it) }==true
        check(if(batch.members.isEmpty()) fromHidden && toHidden else if(batch.direction==VisibilityDirection.HIDE) !fromHidden && toHidden else fromHidden && !toHidden)
        val current=member(change.id,batch.folderId)
        val receipt=state.receipt(change.id)
        if(initial) check(current.folder==change.sourceFolder && current.ref.item.savedUri==change.sourceUri && receipt==change.expectedReceipt)
        if(batch.members.isEmpty()) {
            val expected=checkNotNull(change.expectedReceipt)
            check(receipt==expected || receipt==expected.copy(folderId=requireNotNull(change.targetFolder)))
            verifyHiddenMember(expected,current.ref.item)
        }
    }
    suspend fun prepareMembership(batch:VisibilityBatchRecord,state:CatalogVisibilityState,pending:VisibilityMoveRecord?) {
        batchContext(batch)
        val change=batch.membership ?: return
        // The HIDE member must already belong to the target folder when the
        // existing reference proof is captured. SHOW detaches only after cleanup.
        if(batch.members.isNotEmpty() && batch.direction==VisibilityDirection.HIDE) {
            val receipt=state.receipt(change.id)
            val member=batch.members.single()
            check(pending==null || pending.copy(destinationUri=null,phase=VisibilityMovePhase.PREPARED)==member)
            val allowed=setOfNotNull(change.sourceUri,pending?.destinationUri,
                receipt?.destinationUri?.takeIf { VisibilityReceiptPolicy.confirms(receipt,member) })
            val uri=currentMember(change.id)?.ref?.item?.savedUri ?: change.sourceUri
            check(uri in allowed) { "Адрес файла изменился вне плана переноса" }
            applyAssignment(change,change.targetFolder,uri)
        }
    }
    private suspend fun applyAssignment(change:VisibilityMemberChange,desired:String?,uri:String) {
        if(change.app) database.withTransaction {
            val row=checkNotNull(dao.byId(change.id)); requirePublic(row)
            check(row.savedUri==uri)
            val old=catalog.assignmentById(change.id)
            check(old?.folderId in setOf(change.sourceFolder,change.targetFolder) && (old?.isFavorite==true)==change.favorite)
            if(desired==null && !change.favorite) catalog.removeAssignment(change.id)
            else catalog.saveAssignment(CatalogAssignmentEntity(change.id,desired,change.favorite))
        } else external.assignVisibilityMember(change,desired,uri)
    }
    suspend fun commitMembership(batch:VisibilityBatchRecord,state:CatalogVisibilityState) {
        val change=checkNotNull(batch.membership)
        verifyMembershipIntent(batch,state,false)
        if(batch.members.isEmpty()) {
            val expected=checkNotNull(change.expectedReceipt); val target=checkNotNull(change.targetFolder)
            if(change.app) database.withTransaction {
                applyAssignment(change,target,change.sourceUri)
                state.relocateHiddenReceipt(expected,target)
            } else {
                applyAssignment(change,target,change.sourceUri)
                state.relocateHiddenReceipt(expected,target)
            }
        } else {
            val record=checkNotNull(state.receipt(change.id))
            check(VisibilityReceiptPolicy.confirms(record,batch.members.single()))
            applyAssignment(change,change.targetFolder,requireNotNull(record.destinationUri))
        }
        check(currentMember(change.id)?.folder==change.targetFolder || change.app && change.targetFolder==null && catalog.assignmentById(change.id)==null)
    }

    override suspend fun externalMemberIds(folderId: String): Set<String> = external.albumMemberIds(folderId)
    override suspend fun currentUri(record: VisibilityMoveRecord): String? =
        VisibilityReferencePolicy.currentUri(record,member(record.downloadId,record.folderId).ref.item.savedUri)

    override suspend fun verifyMembership(batch: VisibilityBatchRecord) {
        check(catalog.folderById(batch.folderId) != null)
        if(batch.membership!=null) { member(batch.membership.id,batch.folderId); return }
        val appIds = catalog.memberIds(batch.folderId).toSet()
        val externalIds = externalMemberIds(batch.folderId)
        check(appIds.intersect(externalIds).isEmpty() && appIds + externalIds == batch.members.map { it.downloadId }.toSet()) {
            "Состав папки изменился"
        }
        for (record in batch.members) member(record.downloadId,batch.folderId)
    }

    /** These are current locators, never a previous external entry's accumulated aliases. */
    private suspend fun refFor(member: Member, uri: String, additionalUri: String? = null): LibraryMediaRef {
        val row = member.ref.item
        val identities = PublicMediaIdentity.capture(context,uri) +
            (additionalUri?.let { PublicMediaIdentity.capture(context,it) }.orEmpty()) + member.ref.key
        return member.ref.copy(aliases=identities,item=row.copy(savedUri=uri))
    }
    private fun strings(json: JSONObject, name: String): Set<String> = json.getJSONArray(name).let { array ->
        (0 until array.length()).mapTo(mutableSetOf()) { array.getString(it) }
    }
    private suspend fun evidence(record: VisibilityMoveRecord): JSONObject? = proofs.read(record.operationId)?.let { raw ->
        JSONObject(raw).also { value ->
            check(value.getInt("version") == 1 && value.getString("operation") == record.operationId &&
                value.getString("id") == record.downloadId && value.getString("folder") == record.folderId &&
                value.getString("source") == record.logicalSourceUri && value.getString("target") == record.destinationUri)
        }
    }
    private suspend fun captureEvidence(record: VisibilityMoveRecord, current: Member): JSONObject {
        check(current.ref.item.savedUri == record.logicalSourceUri && current.ref.item.sizeBytes == record.fingerprint.bytes)
        val old = refFor(current,record.logicalSourceUri,record.sourceUri)
        val target = refFor(current,requireNotNull(record.destinationUri))
        val oldKeys = MediaOrganizationPolicy.keys(old)
        val newKeys = MediaOrganizationPolicy.keys(target)
        check(oldKeys.isNotEmpty() && newKeys.isNotEmpty())
        val oldAliases = old.aliases + record.sourceUri + record.logicalSourceUri
        // No other history row or external member may own the same physical source or target.
        val physical = oldAliases + target.aliases
        check(dao.all().filter { it.id != current.ref.appDownloadId }.none { row ->
            listOfNotNull(row.savedUri,row.privateSourceUri).any { uri -> PublicMediaIdentity.uriKeys(uri).any(physical::contains) }
        }) { "Файл связан с другой записью истории" }
        check(external.visibilityEntries().filter { it.ref.key != current.ref.key }.none { entry ->
            entry.ref.item.savedUri?.let { PublicMediaIdentity.uriKeys(it).any(physical::contains) } == true
        }) { "Файл связан с другой записью внешнего каталога" }
        val snapshot = links.snapshot(current.ref.item,oldAliases,record.destinationUri,
            external=current.ref.appDownloadId == null,visibility=true)
        val result = JSONObject().put("version",1).put("operation",record.operationId).put("id",record.downloadId)
            .put("folder",record.folderId).put("source",record.logicalSourceUri).put("target",record.destinationUri)
            .put("favorite",current.favorite).put("external",current.ref.appDownloadId == null)
            .put("name",current.ref.item.originalName).put("date",current.ref.item.createdAtEpochMillis)
            .put("oldKeys",JSONArray(oldKeys.sorted())).put("newKeys",JSONArray(newKeys.sorted()))
            .put("oldAliases",JSONArray(oldAliases.sorted())).put("newAliases",JSONArray(target.aliases.sorted()))
            .put("members",JSONArray(collections.capture(oldKeys).sorted()))
            .put("oldListening",JSONArray(ListeningHistoryPolicy.keys(old.item,old.aliases).sorted()))
            .put("newListening",JSONArray(ListeningHistoryPolicy.keys(target.item,target.aliases).sorted()))
            .put("links",snapshot)
        proofs.write(record.operationId,null,result.toString())
        return result
    }
    private fun targetRef(record: VisibilityMoveRecord, current: Member, proof: JSONObject): LibraryMediaRef {
        val row = current.ref.item
        check(current.favorite == proof.getBoolean("favorite") && (current.ref.appDownloadId == null) == proof.getBoolean("external") &&
            row.originalName == proof.getString("name") && row.createdAtEpochMillis == proof.getLong("date") && row.sizeBytes == record.fingerprint.bytes)
        return current.ref.copy(aliases=strings(proof,"newAliases"),item=row.copy(savedUri=record.destinationUri))
    }
    override suspend fun rebind(record: VisibilityMoveRecord) = withContext(Dispatchers.IO) {
        val current = member(record.downloadId,record.folderId)
        check(currentUri(record) in setOf(record.sourceUri,record.destinationUri))
        val proof = evidence(record) ?: captureEvidence(record,current)
        val target = targetRef(record,current,proof)
        val snapshot = proof.getJSONObject("links")
        val destination = requireNotNull(record.destinationUri)
        if (current.ref.appDownloadId != null) database.withTransaction {
            val row = checkNotNull(dao.byId(record.downloadId))
            if (row.savedUri != destination) {
                check(row.savedUri == record.logicalSourceUri)
                check(dao.rebindVisibilityUri(row.id,record.logicalSourceUri,destination,record.fingerprint.bytes) == 1)
            }
        }
        // Historical links' per-operation atomic snapshot is replayable after the
        // app-row CAS, and covers external catalog, playlist, VK, artwork and video position.
        links.rebind(snapshot,record.logicalSourceUri,destination,record.fingerprint.sha256,
            record.fingerprint.sha256,target.item.originalName,record.fingerprint.bytes)
        collections.rebind(strings(proof,"oldKeys"),target,strings(proof,"members"))
        if (target.item.mediaType == MediaType.AUDIO)
            listening.rebindVerifiedVisibility(strings(proof,"oldListening"),strings(proof,"newListening"))
        verifyBinding(record)
    }
    override suspend fun verifyBinding(record: VisibilityMoveRecord) = withContext(Dispatchers.IO) {
        val proof = checkNotNull(evidence(record)) { "Не найден журнал связей файла" }
        val current = member(record.downloadId,record.folderId)
        val destination = requireNotNull(record.destinationUri)
        check(current.ref.item.savedUri == destination)
        val target = targetRef(record,current,proof)
        check(MediaOrganizationPolicy.keys(target) == strings(proof,"newKeys"))
        links.verify(proof.getJSONObject("links"),destination,record.fingerprint.sha256,addressed=true, membership=activeBatch?.membership)
        collections.verify(strings(proof,"oldKeys"),target,strings(proof,"members"))
        if (target.item.mediaType == MediaType.AUDIO)
            listening.verifyVisibilityAliases(strings(proof,"oldListening"),strings(proof,"newListening"))
    }
    override suspend fun finishBinding(record: VisibilityMoveRecord) = withContext(Dispatchers.IO) {
        check(record.phase == VisibilityMovePhase.CLEANED)
        val proof = checkNotNull(evidence(record))
        val snapshot = proof.getJSONObject("links")
        links.compactVisibility(snapshot,requireNotNull(record.destinationUri),record.fingerprint.sha256)
        if (snapshot.length() > 1) {
            val before = proof.toString()
            proof.put("links",JSONObject().put("key",snapshot.getString("key")))
            proofs.write(record.operationId,before,proof.toString())
        }
    }
    override suspend fun verifySourceUnreferenced(record: VisibilityMoveRecord) = withContext(Dispatchers.IO) {
        val proof = checkNotNull(evidence(record))
        val old = strings(proof,"oldAliases")
        check(dao.all().none { row -> listOfNotNull(row.savedUri,row.privateSourceUri,row.previewUrl,
            row.sourceAudioArtworkUrl,row.recognitionArtworkUrl,row.sourceUrl,row.sourcePageUrl).any { uri -> uri in old || PublicMediaIdentity.uriKeys(uri).any(old::contains) } }) {
            "Остались живые ссылки на исходный файл"
        }
        check(external.visibilityEntries().none { entry -> entry.ref.item.savedUri?.let { it in old || PublicMediaIdentity.uriKeys(it).any(old::contains) } == true })
    }
}
