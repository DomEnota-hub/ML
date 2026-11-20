package com.domenota.medialoader.data.catalog.visibility

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Independent of the v1/v2 physical HIDE/SHOW engine, PrivateFileStore and Room history.
 * The only permitted physical mutation is create/delete of the exact .nomedia document
 * in a user-authorized external-storage SAF tree.
 */
data class NomediaSafEntry(
    val documentId:String,
    val displayName:String,
    val mimeType:String,
    val byteCount:Long?,
)

/** A scoped, read-only SAF scan; never imports the document into MediaStore or history. */
internal class NomediaFolderRepository(context: Context) : NomediaFolderStore {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("folder_nomedia_v1",Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val current = MutableStateFlow(load())
    val snapshot = current.asStateFlow()
    private val safPort = AndroidNomediaMarkerPort(this.context)
    private val safEngine = NomediaFolderEngine(this,safPort)
    private fun engine(record:NomediaFolderRecord) = if(record.accessMode==NomediaAccessMode.MANAGED_LOCAL)
        NomediaFolderEngine(this,ManagedLocalNomediaPort()) else safEngine

    override fun snapshot():NomediaFolderSnapshot = current.value

    override fun write(record:NomediaFolderRecord) {
        val before = current.value
        check(!before.unknown) { "Повреждённый реестр скрытия" }
        val updated = NomediaFolderSnapshot(before.folders + (record.folderId to record))
        val payload = encode(updated)
        check(preferences.edit().putString(KEY,payload).commit()) {
            "Не удалось сохранить контрольную точку скрытия"
        }
        current.value = updated
    }

    /** Verify complete real SAF media inventory before writing a directory-wide marker.
     * Never hide unrelated images/audio/video, even if the UI folder is virtual.
     * This does not move files or rewrite any existing MediaStore links.
     */
    suspend fun hideVerified(plan:NomediaAutoFolderPlan):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) {
            // When the existing Android all-files permission is available, no
            // separate folder picker is needed. Legacy SAF records remain SAF.
            val direct=FolderVisibilityStorageAccess.granted()
            val local=if(direct) ManagedLocalNomediaPort(plan.expected) else null
            val found=if(local!=null) local.listMedia(plan.treeUri.toString())
                else safPort.listMedia(plan.treeUri.toString())
            val actual=found.map { NomediaMediaSignature(it.displayName,it.byteCount ?: -1L) }
            NomediaSafTrace.event("INVENTORY_READ",actual.size)
            check(nomediaInventoryMatches(plan.expected,actual)) {
                "Каталог содержит другие файлы или состав изменился. Скрытие всей папки отменено"
            }
            NomediaSafTrace.event("INVENTORY_EXACT_MATCH",actual.size)
            val record=if(local!=null)
                NomediaFolderEngine(this@NomediaFolderRepository,local).hide(
                    plan.folderId,plan.treeUri.toString(),NomediaAccessMode.MANAGED_LOCAL)
            else safEngine.hide(plan.folderId,plan.treeUri.toString(),NomediaAccessMode.SAF)
            NomediaSafTrace.event("MARKER_STATE_"+record.markerState.name,found.size)
            record
        }
    }
    suspend fun hide(folderId:String,treeUri:Uri):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) { safEngine.hide(folderId,treeUri.toString()) }
    }
    suspend fun show(folderId:String):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) {
            val record=checkNotNull(current.value.takeUnless{it.unknown}?.folders?.get(folderId))
            engine(record).show(folderId)
        }
    }
    suspend fun reconcile(folderId:String):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) {
            val record=checkNotNull(current.value.takeUnless{it.unknown}?.folders?.get(folderId))
            engine(record).reconcile(folderId)
        }
    }
    suspend fun releaseShownFolder(folderId:String) = gate.withLock {
        val old=current.value
        check(!old.unknown) { "Реестр скрытия повреждён" }
        val record=old.folders[folderId] ?: return@withLock
        check(!record.excludedFromMl && record.ownedDocumentId==null &&
            record.markerState in setOf(NomediaMarkerState.NO_MARKER,NomediaMarkerState.FOREIGN_MARKER)) {
            "Сначала верните видимость и проверьте служебный маркер"
        }
        val newer=NomediaFolderSnapshot(old.folders-folderId)
        check(preferences.edit().putString(KEY,encode(newer)).commit()) {
            "Не удалось сохранить удаление записи реестра"
        }
        current.value=newer
    }

    /** Explicit SAF browse works even after Android removes the old MediaStore row. */
    suspend fun listHiddenDocuments(folderId:String):List<NomediaSafEntry> = gate.withLock {
        val snapshot=current.value
        check(!snapshot.unknown) { "Реестр скрытия повреждён" }
        val record=checkNotNull(snapshot.folders[folderId]) { "Нет SAF-привязки папки" }
        check(record.excludedFromMl) { "Просмотр SAF доступен только для скрытой папки" }
        if(record.accessMode==NomediaAccessMode.MANAGED_LOCAL)
            ManagedLocalNomediaPort().listMedia(record.treeUri)
        else safPort.listMedia(record.treeUri)
    }

    /** A temporary playback/view URI under the same already-approved SAF grant.
     * It never replaces DownloadEntity.savedUri or an existing MediaStore locator. */
    suspend fun openSafMedia(folderId:String,documentId:String):Uri = gate.withLock {
        val record=checkNotNull(current.value.takeUnless { it.unknown }?.folders?.get(folderId)) {
            "Состояние доступа неизвестно"
        }
        check(record.excludedFromMl) { "Папка не скрыта в ML" }
        if(record.accessMode==NomediaAccessMode.MANAGED_LOCAL)
            ManagedLocalNomediaPort().localMediaUri(record.treeUri,documentId)
        else safPort.readableMediaUri(record.treeUri,documentId)
    }

    suspend fun verifySafDocumentRead(folderId:String,documentId:String):Boolean = gate.withLock {
        val record=checkNotNull(current.value.takeUnless { it.unknown }?.folders?.get(folderId)) {
            "Состояние доступа неизвестно"
        }
        check(record.excludedFromMl)
        if(record.accessMode==NomediaAccessMode.MANAGED_LOCAL) {
            val uri=ManagedLocalNomediaPort().localMediaUri(record.treeUri,documentId)
            runCatching { java.io.FileInputStream(checkNotNull(uri.path)).use { it.read();true } }
                .getOrDefault(false)
        } else safPort.verifyMediaRead(record.treeUri,documentId)
    }

    /** Returns display-only status; never claims that Xiaomi has rescanned. */
    fun statusText(folderId:String):String? {
        val record=current.value.folders[folderId] ?: return null
        val ml=if(record.excludedFromMl) "ML: скрыто" else "ML: видно"
        val android=when(record.markerState) {
            NomediaMarkerState.OWN_MARKER -> ".nomedia создан; Android не проверен"
            NomediaMarkerState.FOREIGN_MARKER -> "чужой .nomedia сохранён"
            NomediaMarkerState.NO_MARKER -> ".nomedia отсутствует"
            NomediaMarkerState.PENDING_HIDE,NomediaMarkerState.PENDING_SHOW -> "проверка не завершена"
            NomediaMarkerState.FAILED -> "ошибка; требуется проверка"
        }
        return "$ml · $android"
    }

    /** No URI is inferred from a stale MediaStore ID. This checks only the old link. */
    suspend fun checkOldMediaLink(savedUri:String?):Boolean = withContext(Dispatchers.IO) {
        val uri = savedUri?.takeIf { it.startsWith("content://") } ?: return@withContext false
        try {
            context.contentResolver.openFileDescriptor(Uri.parse(uri),"r")?.use { fd ->
                fd.statSize >= 0 || fd.fileDescriptor.valid()
            } == true
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { false }
    }

    private fun load():NomediaFolderSnapshot { return try {
        val payload=preferences.getString(KEY,null) ?: return NomediaFolderSnapshot()
        check(payload.length <= 1024*1024) { "Реестр превышает лимит" }
        val root=JSONObject(payload)
        check(root.getInt("version")==1)
        val entries=root.getJSONArray("entries");check(entries.length()<=1024)
        val result=linkedMapOf<String,NomediaFolderRecord>()
        for(i in 0 until entries.length()) {
            val e=entries.getJSONObject(i)
            val folderId=e.getString("folderId")
            val marker=NomediaMarkerState.valueOf(e.getString("markerState"))
            val record=NomediaFolderRecord(
                folderId=folderId,treeUri=e.getString("treeUri"),
                mlHidden=e.getBoolean("mlHidden"),markerState=marker,
                ownedDocumentId=e.optString("ownedDocumentId").takeIf { it.isNotBlank() },
                errorCode=e.optString("errorCode").takeIf { it.isNotBlank() },
                updatedAt=e.getLong("updatedAt"),
                accessMode=NomediaAccessMode.valueOf(e.optString("accessMode","SAF")),
                ownedMarkerBytes=e.optLong("ownedMarkerBytes",0L))
            check(result.put(folderId,record)==null) { "Повторный folderId" }
            check(record.treeUri.startsWith("content://"))
            check(record.accessMode!=NomediaAccessMode.MANAGED_LOCAL ||
                (android.net.Uri.parse(record.treeUri).authority=="com.android.externalstorage.documents" &&
                    nomediaSafTreeEligible(android.provider.DocumentsContract.getTreeDocumentId(
                        android.net.Uri.parse(record.treeUri)))))
            check(record.ownedDocumentId==null ||
                record.accessMode==NomediaAccessMode.SAF ||
                record.ownedDocumentId.startsWith("ml-archive-v1:"))
            check(marker!=NomediaMarkerState.OWN_MARKER || record.ownedDocumentId!=null)
            check(record.ownedDocumentId==null || marker in setOf(NomediaMarkerState.OWN_MARKER,
                NomediaMarkerState.PENDING_SHOW,NomediaMarkerState.FAILED))
            check(record.mlHidden || marker !in setOf(NomediaMarkerState.PENDING_HIDE,
                NomediaMarkerState.OWN_MARKER))
        }
        NomediaFolderSnapshot(result)
    } catch(_:Exception) {
        // Never turn a damaged persisted hidden-folder record into an empty public list.
        NomediaFolderSnapshot(unknown=true)
    } }

    private fun encode(snapshot:NomediaFolderSnapshot):String {
        check(!snapshot.unknown && snapshot.folders.size<=1024)
        val entries=JSONArray()
        snapshot.folders.values.sortedBy { it.folderId }.forEach { record ->
            entries.put(JSONObject().apply {
                put("folderId",record.folderId);put("treeUri",record.treeUri)
                put("mlHidden",record.mlHidden);put("markerState",record.markerState.name)
                record.ownedDocumentId?.let { put("ownedDocumentId",it) }
                record.errorCode?.let { put("errorCode",it) }
                put("updatedAt",record.updatedAt)
                if(record.accessMode!=NomediaAccessMode.SAF)put("accessMode",record.accessMode.name)
                if(record.ownedMarkerBytes>0L)put("ownedMarkerBytes",record.ownedMarkerBytes)
            })
        }
        val raw=JSONObject().put("version",1).put("entries",entries).toString()
        check(raw.length<=1024*1024)
        return raw
    }
    companion object { private const val KEY="registry" }
}

/** Prevent accidentally excluding an entire top-level phone gallery or old physical archive.
 * This is intentionally conservative: subfolders are selected explicitly in Android SAF. */
internal fun nomediaSafTreeEligible(documentId:String):Boolean {
    if(documentId.length !in 3..512) return false
    val volume=documentId.substringBefore(':',"")
    val path=documentId.substringAfter(':',"")
    if(volume.isBlank() || path.isBlank() || path.contains('\\')) return false
    val parts=path.split('/')
    if(parts.any { it.isBlank() || it=="." || it==".." }) return false
    val normalized=parts.map { it.lowercase(java.util.Locale.ROOT) }
    if(normalized.size==1 && normalized[0] in setOf(
            "pictures","dcim","music","movies","download","downloads","documents","android")) return false
    if(normalized.first()=="android") return false
    if(normalized.size>=2 && normalized[0]=="documents" && normalized[1]=="medialoaderhidden") return false
    return true
}

/** Restricted to Android's physical external-storage documents provider.
 * Cloud/third-party providers cannot claim system-gallery exclusion. */
internal class AndroidNomediaMarkerPort(context:Context):NomediaMarkerPort {
    private val resolver:ContentResolver=context.applicationContext.contentResolver
    private data class Marker(val uri:Uri,val proof:NomediaMarkerProof)

    private fun authorizedTree(raw:String):Uri {
        val tree=Uri.parse(raw)
        check(tree.scheme=="content" && tree.authority=="com.android.externalstorage.documents") {
            "Требуется папка локального хранилища Android"
        }
        val documentId=DocumentsContract.getTreeDocumentId(tree)
        check(nomediaSafTreeEligible(documentId)) {
            "Корневой, системный или прежний архивный каталог выбрать нельзя"
        }
        check(resolver.persistedUriPermissions.any { p ->
            p.uri==tree && p.isReadPermission && p.isWritePermission
        }) { "Доступ к папке отозван. Выберите её повторно" }
        return tree
    }

    private fun marker(raw:String):Marker? {
        val tree=authorizedTree(raw)
        val folderId=DocumentsContract.getTreeDocumentId(tree)
        val child=DocumentsContract.buildChildDocumentsUriUsingTree(tree,folderId)
        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
        val matching=mutableListOf<Marker>()
        val cursor=checkNotNull(resolver.query(child,columns,null,null,null)) {
            "Каталог SAF не возвращает список файлов"
        }
        cursor.use {
            while(it.moveToNext()) {
                if(it.getString(1)!=".nomedia") continue
                val id=it.getString(0)
                check(!id.isNullOrBlank() && it.getString(2)!=DocumentsContract.Document.MIME_TYPE_DIR) {
                    "Служебный маркер повреждён"
                }
                val uri=DocumentsContract.buildDocumentUriUsingTree(tree,id)
                val known=if(it.isNull(3)) -1L else it.getLong(3)
                val size=if(known>=0L) known else
                    checkNotNull(resolver.openInputStream(uri)) { "Невозможно проверить .nomedia" }.use { stream ->
                        if(stream.read()==-1) 0L else 1L // bounded probe, not a copy
                    }
                matching+=Marker(uri,NomediaMarkerProof(id,size))
                check(matching.size==1) { "Несколько .nomedia в одной папке" }
            }
        }
        return matching.singleOrNull()
    }

    /** Bounded tree enumeration; no copies or guessed download-id associations. */
    suspend fun listMedia(treeUri:String):List<NomediaSafEntry> = withContext(Dispatchers.IO) {
        val tree=authorizedTree(treeUri)
        val queue=java.util.ArrayDeque<Pair<String,Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val visited=mutableSetOf<String>()
        val entries=mutableListOf<NomediaSafEntry>()
        var scanned=0
        val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE)
        while(queue.isNotEmpty()) {
            val (parent,depth)=queue.removeFirst()
            check(visited.add(parent)) { "SAF содержит повторную ссылку" }
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,parent)
            val cursor=checkNotNull(resolver.query(children,columns,null,null,null)) {
                "Дерево SAF недоступно"
            }
            cursor.use {
                while(it.moveToNext()) {
                    check(++scanned <= 10_000) { "Каталог слишком велик для просмотра" }
                    val id=it.getString(0)
                    val name=it.getString(1)
                    val mime=it.getString(2)
                    check(!id.isNullOrBlank() && !name.isNullOrBlank() && !mime.isNullOrBlank()) {
                        "Неполная запись SAF"
                    }
                    if(name==".nomedia") continue
                    if(mime==DocumentsContract.Document.MIME_TYPE_DIR) {
                        check(depth<12) { "Дерево SAF слишком глубокое" }
                        queue.add(id to (depth+1))
                    } else if(mime.startsWith("image/") || mime.startsWith("video/") ||
                        mime.startsWith("audio/")) {
                        check(entries.size<2048) { "Слишком много медиа в выбранном каталоге" }
                        entries+=NomediaSafEntry(id,name,mime,
                            if(it.isNull(3)) null else it.getLong(3).takeIf { v -> v>=0 })
                    }
                }
            }
        }
        entries
    }

    /** URI is derived only from a currently listed document in the same granted tree. */
    suspend fun readableMediaUri(treeUri:String,documentId:String):Uri = withContext(Dispatchers.IO) {
        check(documentId.isNotBlank())
        val entry=checkNotNull(listMedia(treeUri).singleOrNull { it.documentId==documentId }) {
            "Документ SAF не найден"
        }
        check(entry.mimeType.startsWith("image/") || entry.mimeType.startsWith("video/") ||
            entry.mimeType.startsWith("audio/"))
        val tree=authorizedTree(treeUri)
        val uri=DocumentsContract.buildDocumentUriUsingTree(tree,entry.documentId)
        val readable=try {
            resolver.openFileDescriptor(uri,"r")?.use { fd -> fd.fileDescriptor.valid() }==true
        } catch(cancelled:CancellationException){throw cancelled}
        catch(_:Exception){false}
        check(readable) { "Документ SAF недоступен для чтения" }
        uri
    }

    /** Exactly one allowed scoped media read; ID must occur in the current SAF inventory. */
    suspend fun verifyMediaRead(treeUri:String,documentId:String):Boolean = withContext(Dispatchers.IO) {
        val entry=listMedia(treeUri).singleOrNull { it.documentId==documentId }
            ?: return@withContext false
        val tree=authorizedTree(treeUri)
        val document=DocumentsContract.buildDocumentUriUsingTree(tree,entry.documentId)
        try {
            resolver.openInputStream(document)?.use { it.read();true }==true
        } catch(cancelled:CancellationException) { throw cancelled }
        catch(_:Exception) { false }
    }

    override suspend fun inspect(treeUri:String):NomediaMarkerProof? =
        withContext(Dispatchers.IO) { marker(treeUri)?.proof }

    override suspend fun createEmpty(treeUri:String):NomediaMarkerProof = withContext(Dispatchers.IO) {
        check(marker(treeUri)==null) { ".nomedia уже существует" }
        val tree=authorizedTree(treeUri)
        val parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
        val created=checkNotNull(DocumentsContract.createDocument(resolver,parent,
            "application/octet-stream",".nomedia")) { "Android отказал в создании .nomedia" }
        val proof=checkNotNull(marker(treeUri)) { "Созданный маркер не обнаружен" }.proof
        check(proof.documentId==DocumentsContract.getDocumentId(created) && proof.bytes==0L) {
            "Созданный .nomedia не подтверждён"
        }
        proof
    }

    override suspend fun deleteVerified(treeUri:String,documentId:String):Boolean = withContext(Dispatchers.IO) {
        val found=checkNotNull(marker(treeUri)) { "Служебный маркер отсутствует" }
        check(found.proof.documentId==documentId && found.proof.bytes==0L) {
            "Служебный маркер заменён. Удаление запрещено"
        }
        DocumentsContract.deleteDocument(resolver,found.uri)
    }
}
