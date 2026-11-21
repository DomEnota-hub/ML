package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolve the *actual* local directory from authoritative MediaStore entries.
 * No guessed /storage paths, relocated media, or inferred SAF permissions.
 * Only one exact physical directory may be excluded with one .nomedia marker.
 */
internal data class NomediaMediaSignature(val name:String, val bytes:Long)
internal data class NomediaAutoFolderPlan(
    val folderId:String,
    val treeUri:Uri,
    val expected:List<NomediaMediaSignature>,
)

internal fun nomediaSuggestedTree(volume:String?, relativePath:String?):Uri? {
    if(volume != MediaStore.VOLUME_EXTERNAL_PRIMARY) return null
    val path=relativePath?.removeSuffix("/") ?: return null
    if(path.isBlank() || path.startsWith("/") || path.endsWith("/") ||
        path.contains('\\') || path.contains(':') ||
        path.split('/').any { it.isBlank() || it=="." || it==".." }) return null
    val id="primary:$path"
    if(!nomediaSafTreeEligible(id))return null
    return DocumentsContract.buildTreeDocumentUri("com.android.externalstorage.documents",id)
}

/** Each real SAF media file must belong to the selected virtual ML folder.
 * Duplicate names with different size, missing size and extra media all fail closed.
 */
internal fun nomediaInventoryMatches(
    expected:List<NomediaMediaSignature>,
    found:List<NomediaMediaSignature>,
):Boolean {
    if(expected.isEmpty() || found.size!=expected.size) return false
    fun histogram(files:List<NomediaMediaSignature>):Map<NomediaMediaSignature,Int> =
        files.groupingBy{it}.eachCount()
    return expected.all{it.name.isNotBlank() && it.bytes>=0} &&
        found.all{it.name.isNotBlank() && it.bytes>=0} &&
        histogram(expected)==histogram(found)
}

/** Distinguish an actual physical directory from an ML virtual collection.
 * A .nomedia marker can NEVER be used for a proper subset of a directory. */
internal sealed interface ArchiveInventoryVerdict {
    data object ExactPhysicalFolder : ArchiveInventoryVerdict
    data class RequiresSelectiveArchive(val selected:Int,val physical:Int) : ArchiveInventoryVerdict
    data object Unverified : ArchiveInventoryVerdict
}

internal fun archiveInventoryVerdict(
    selected:List<NomediaMediaSignature>,
    physical:List<NomediaMediaSignature>,
):ArchiveInventoryVerdict {
    if(nomediaInventoryMatches(selected,physical)) return ArchiveInventoryVerdict.ExactPhysicalFolder
    if(selected.isEmpty() || selected.any { it.name.isBlank() || it.bytes < 0 } ||
        physical.any { it.name.isBlank() || it.bytes < 0 }) return ArchiveInventoryVerdict.Unverified
    val available=physical.groupingBy { it }.eachCount()
    val wanted=selected.groupingBy { it }.eachCount()
    if(wanted.all { (entry,count) -> count <= (available[entry] ?: 0) } &&
        physical.size > selected.size) {
        return ArchiveInventoryVerdict.RequiresSelectiveArchive(selected.size,physical.size)
    }
    return ArchiveInventoryVerdict.Unverified
}

internal suspend fun resolveNomediaAutoPlan(
    context:Context,
    folderId:String,
    downloads:List<DownloadEntity>,
    assignments:List<CatalogAssignmentEntity>,
):NomediaAutoFolderPlan=withContext(Dispatchers.IO) {
    check(Build.VERSION.SDK_INT>=30) { "Автопоиск папки требует Android 11 или новее" }
    val ids=assignments.asSequence().filter{it.folderId==folderId}.map{it.downloadId}.toSet()
    check(ids.isNotEmpty()) { "В папке нет доступных медиа" }
    val rows=downloads.filter{it.id in ids}
    check(rows.size==ids.size && rows.all{
        it.state==DownloadState.COMPLETED && !it.hidden && !it.isPrivate && !it.savedUri.isNullOrBlank()
    }) { "Не все файлы папки доступны для безопасного скрытия" }
    val entries=rows.distinctBy{it.savedUri}
    check(entries.size in 1..2048) { "Количество файлов для проверки не поддерживается" }
    val resolver=context.contentResolver
    val fields=arrayOf(MediaStore.MediaColumns.RELATIVE_PATH,
        MediaStore.MediaColumns.VOLUME_NAME,MediaStore.MediaColumns.DISPLAY_NAME,
        MediaStore.MediaColumns.SIZE)
    val candidates=entries.map{row->
        val mediaUri=Uri.parse(requireNotNull(row.savedUri))
        check(mediaUri.scheme=="content" && mediaUri.authority=="media") {
            "Для этих файлов нет подтверждённого расположения в Android"
        }
        val record=checkNotNull(resolver.query(mediaUri,fields,null,null,null)){
            "Не удалось узнать расположение медиа"
        }.use{cursor->
            check(cursor.moveToFirst() && cursor.count==1) { "Медиафайл отсутствует в Android" }
            val tree=nomediaSuggestedTree(cursor.getString(1),cursor.getString(0))
            check(tree!=null) { "Этот каталог нельзя скрыть целиком" }
            val name=cursor.getString(2)
            val bytes=cursor.getLong(3)
            check(!name.isNullOrBlank() && '/' !in name && '\\' !in name && bytes>=0L) {
                "Невозможно подтвердить состав папки"
            }
            tree to NomediaMediaSignature(name,bytes)
        }
        record
    }
    val trees=candidates.map{it.first}.distinct()
    check(trees.size==1) {
        "Файлы находятся в нескольких каталогах. Скрытие без переноса пока недоступно"
    }
    NomediaAutoFolderPlan(folderId,trees.single(),candidates.map{it.second})
}
