package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.provider.MediaStore
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * READ ONLY. Every source is queried by its existing MediaStore URI and hashed
 * between two independent identity snapshots. No path inference or write occurs.
 */
internal data class SelectiveArchiveCatalogMember(
    val catalogId:String,
    val originalUri:String,
    val referencesSha256:String,
)
internal data class SelectiveArchiveMediaState(
    val originalUri:String,
    val mediaStoreId:Long,
    val volume:String,
    val relativePath:String,
    val displayName:String,
    val bytes:Long,
    /** MediaStore DATE_MODIFIED expressed in whole seconds, converted to millis. */
    val modifiedAtMillis:Long,
    val mime:String,
    val pending:Boolean,
    val trashed:Boolean,
) {
    fun publicAndComplete():Boolean =
        !pending && !trashed && bytes >= 0 && modifiedAtMillis > 0 &&
            (mime.startsWith("audio/") || mime.startsWith("image/") || mime.startsWith("video/"))
}
internal interface SelectiveArchiveMediaProbe {
    suspend fun inspect(uri:String):SelectiveArchiveMediaState?
    suspend fun fingerprint(uri:String):String?
}

/** Android source reader has no filesystem mutation APIs. */
internal class AndroidSelectiveArchiveMediaProbe(context:Context):SelectiveArchiveMediaProbe {
    private val resolver=context.applicationContext.contentResolver
    override suspend fun inspect(uri:String):SelectiveArchiveMediaState?=withContext(Dispatchers.IO) {
        check(Build.VERSION.SDK_INT >= 30) { "Требуется Android 11 или новее" }
        val parsed=Uri.parse(uri)
        check(parsed.scheme=="content" && parsed.authority==MediaStore.AUTHORITY) {
            "Нужен точный MediaStore URI"
        }
        val columns=arrayOf(BaseColumns._ID,MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,MediaStore.MediaColumns.IS_PENDING,
            MediaStore.MediaColumns.IS_TRASHED)
        val cursor=checkNotNull(resolver.query(parsed,columns,null,null,null)) {
            "MediaStore не подтвердил источник"
        }
        cursor.use {
            if(!it.moveToFirst())return@withContext null
            check(it.count==1)
            val id=it.getLong(0)
            val volume=it.getString(1) ?: ""
            val path=it.getString(2) ?: ""
            val name=it.getString(3) ?: ""
            val bytes=if(it.isNull(4))-1L else it.getLong(4)
            val date=if(it.isNull(5))-1L else Math.multiplyExact(it.getLong(5),1000L)
            val mime=it.getString(6) ?: ""
            val pending=!it.isNull(7) && it.getInt(7)!=0
            val trashed=!it.isNull(8) && it.getInt(8)!=0
            SelectiveArchiveMediaState(uri,id,volume,path,name,bytes,date,mime,pending,trashed)
        }
    }
    override suspend fun fingerprint(uri:String):String?=withContext(Dispatchers.IO) {
        val parsed=Uri.parse(uri)
        check(parsed.scheme=="content" && parsed.authority==MediaStore.AUTHORITY)
        val digest=MessageDigest.getInstance("SHA-256")
        val input=resolver.openInputStream(parsed) ?: return@withContext null
        input.use {
            val buffer=ByteArray(64*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val count=it.read(buffer)
                if(count<0)break
                if(count>0)digest.update(buffer,0,count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

internal class SelectiveArchiveSourceInspector(private val probe:SelectiveArchiveMediaProbe) {
    /**
     * Only a STRICT subset of an already inventoried physical folder is
     * eligible for source inspection. This does not permit a subsequent move.
     * Reference digests must be supplied by an independently complete catalog
     * snapshot, not guessed from the filename or URI.
     */
    suspend fun inspect(
        selected:List<SelectiveArchiveCatalogMember>,
        signatures:List<NomediaMediaSignature>,
        physical:List<NomediaMediaSignature>,
    ):List<SelectiveArchiveIdentity> = withContext(Dispatchers.IO) {
        check(selected.size in 1..2048 && selected.size==signatures.size)
        check(archiveInventoryVerdict(signatures,physical) is
            ArchiveInventoryVerdict.RequiresSelectiveArchive) {
            "Набор не является подтверждённой частью физического каталога"
        }
        check(selected.map { it.catalogId }.distinct().size==selected.size &&
            selected.map { it.originalUri }.distinct().size==selected.size)
        val originals=selected.map { member ->
            val first=checkNotNull(probe.inspect(member.originalUri)) {
                "Не удалось проверить исходный MediaStore ID"
            }
            check(first.publicAndComplete() && first.originalUri==member.originalUri) {
                "Исходный файл не является доступным публичным медиа"
            }
            val sha=checkNotNull(probe.fingerprint(member.originalUri)) {
                "Не удалось прочитать исходный файл"
            }
            val again=checkNotNull(probe.inspect(member.originalUri)) {
                "Исходная запись исчезла во время чтения"
            }
            check(first==again) {
                "Источник изменился во время контрольного чтения"
            }
            SelectiveArchiveIdentity(
                member.catalogId,member.originalUri,first.mediaStoreId,first.volume,
                first.relativePath,first.displayName,first.bytes,sha,
                first.modifiedAtMillis,member.referencesSha256)
        }
        check(verifySelectiveArchiveSources(originals,signatures,physical)==null) {
            "Проверка связей, идентификаторов или физической описи не пройдена"
        }
        originals
    }
}
