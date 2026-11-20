package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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
internal data class SelectiveArchivePayloadDigest(val bytes:Long,val sha256:String)
internal interface SelectiveArchiveMediaProbe {
    suspend fun inspect(uri:String):SelectiveArchiveMediaState?
    suspend fun fingerprint(uri:String):SelectiveArchivePayloadDigest?
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
            MediaStore.MediaColumns.IS_TRASHED,MediaStore.MediaColumns.DATA)
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
            check(!it.isNull(7) && !it.isNull(8)) {
                "MediaStore не подтвердил статус публичности файла"
            }
            val pending=it.getInt(7)!=0
            val trashed=it.getInt(8)!=0
            val data=it.getString(9) ?: ""
            // MediaStore DATE_MODIFIED has only seconds of precision.
            // Preserve the actual filesystem millisecond timestamp or refuse
            // to create identity evidence for a future restorative operation.
            val precise=preciseModifiedMillis(volume,path,name,data,date)
            SelectiveArchiveMediaState(uri,id,volume,path,name,bytes,precise,mime,pending,trashed)
        }
    }
    private fun preciseModifiedMillis(
        volume:String,relative:String,name:String,data:String,providerMillis:Long,
    ):Long {
        check(FolderVisibilityStorageAccess.granted() &&
            volume==MediaStore.VOLUME_EXTERNAL_PRIMARY) {
            "Для точного сохранения исходной даты нужен проверенный локальный доступ"
        }
        val root=Environment.getExternalStorageDirectory().canonicalFile
        val actual=File(data).absoluteFile
        val expected=File(root,relative+name)
        check(data.isNotBlank() && actual.canonicalFile==expected.canonicalFile &&
            actual.canonicalFile==actual && actual.toPath().startsWith(root.toPath()) &&
            Files.isRegularFile(actual.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(actual.toPath())) {
            "Точный исходный путь файла не подтверждён"
        }
        val mtime=Files.getLastModifiedTime(actual.toPath(),NOFOLLOW_LINKS).toMillis()
        check(providerMillis>0 && mtime>=0 &&
            kotlin.math.abs(mtime-providerMillis)<1000L) {
            "Дата Android и физического файла не совпадает"
        }
        return mtime
    }
    override suspend fun fingerprint(uri:String):SelectiveArchivePayloadDigest?=withContext(Dispatchers.IO) {
        val parsed=Uri.parse(uri)
        check(parsed.scheme=="content" && parsed.authority==MediaStore.AUTHORITY)
        val digest=MessageDigest.getInstance("SHA-256")
        val input=resolver.openInputStream(parsed) ?: return@withContext null
        var bytes=0L
        input.use {
            val buffer=ByteArray(64*1024)
            while(true) {
                currentCoroutineContext().ensureActive()
                val count=it.read(buffer)
                if(count<0)break
                if(count>0) {
                    digest.update(buffer,0,count)
                    bytes=Math.addExact(bytes,count.toLong())
                }
            }
        }
        SelectiveArchivePayloadDigest(bytes,
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
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
            val digest=checkNotNull(probe.fingerprint(member.originalUri)) {
                "Не удалось прочитать исходный файл"
            }
            val again=checkNotNull(probe.inspect(member.originalUri)) {
                "Исходная запись исчезла во время чтения"
            }
            check(first==again && first.bytes==digest.bytes) {
                "Источник изменился во время контрольного чтения"
            }
            SelectiveArchiveIdentity(
                member.catalogId,member.originalUri,first.mediaStoreId,first.volume,
                first.relativePath,first.displayName,first.bytes,digest.sha256,
                first.modifiedAtMillis,member.referencesSha256)
        }
        check(verifySelectiveArchiveSources(originals,signatures,physical)==null) {
            "Проверка связей, идентификаторов или физической описи не пройдена"
        }
        originals
    }
}
