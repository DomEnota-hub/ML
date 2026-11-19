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
internal class NomediaFolderRepository(context: Context) : NomediaFolderStore {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("folder_nomedia_v1",Context.MODE_PRIVATE)
    private val gate = Mutex()
    private val current = MutableStateFlow(load())
    val snapshot = current.asStateFlow()
    private val engine = NomediaFolderEngine(this,AndroidNomediaMarkerPort(this.context))

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

    suspend fun hide(folderId:String,treeUri:Uri):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) { engine.hide(folderId,treeUri.toString()) }
    }
    suspend fun show(folderId:String):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) { engine.show(folderId) }
    }
    suspend fun reconcile(folderId:String):NomediaFolderRecord = gate.withLock {
        withContext(Dispatchers.IO) { engine.reconcile(folderId) }
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
                updatedAt=e.getLong("updatedAt"))
            check(result.put(folderId,record)==null) { "Повторный folderId" }
            check(record.treeUri.startsWith("content://"))
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
            })
        }
        val raw=JSONObject().put("version",1).put("entries",entries).toString()
        check(raw.length<=1024*1024)
        return raw
    }
    companion object { private const val KEY="registry" }
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
        check(documentId.isNotBlank() && documentId != "primary:") {
            "Корневой каталог выбрать нельзя"
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
