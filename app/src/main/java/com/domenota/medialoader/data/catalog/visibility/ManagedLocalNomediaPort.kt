package com.domenota.medialoader.data.catalog.visibility

import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.domenota.medialoader.core.logging.AppLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/**
 * No SAF picker when the user has already granted ML all-files access.
 * The requested tree is NOT a filesystem permission: only a directory whose
 * physical MediaStore location has been independently verified can be used.
 * Absolutely no ordinary media bytes, names or dates are modified here.
 */
internal class ManagedLocalNomediaPort(
    private val expected: List<NomediaMediaSignature>? = null,
    private val storageRoot: () -> File = { Environment.getExternalStorageDirectory().canonicalFile },
    private val hasPermission: () -> Boolean = { FolderVisibilityStorageAccess.granted() },
    private val mimeForExtension: (String) -> String? = {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
    },
) : NomediaMarkerPort {
    private val prefix = "ML-ARCHIVE-v1:"
    private val idPrefix = "ml-archive-v1:"

    private fun folder(raw: String): File {
        check(Build.VERSION.SDK_INT >= 30 && hasPermission()) {
            "Для автоматического доступа к папке требуется разрешение Android на управление файлами"
        }
        val uri = Uri.parse(raw)
        check(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents")
        val doc = DocumentsContract.getTreeDocumentId(uri)
        check(doc.startsWith("primary:") && nomediaSafTreeEligible(doc)) {
            "Неподтверждённый или слишком широкий каталог"
        }
        check(uri == DocumentsContract.buildTreeDocumentUri(uri.authority!!, doc)) {
            "Адрес физического каталога отличается от ожидаемого"
        }
        val primary = storageRoot().canonicalFile
        check(Files.isDirectory(primary.toPath(), NOFOLLOW_LINKS))
        var current = primary
        for (segment in doc.substringAfter(':').split('/')) {
            check(segment.isNotBlank() && segment != "." && segment != "..")
            current = File(current, segment)
            check(!Files.isSymbolicLink(current.toPath()) &&
                Files.isDirectory(current.toPath(), NOFOLLOW_LINKS)) {
                "Папка не найдена или содержит недопустимую ссылку"
            }
        }
        check(current.canonicalFile == current && current.path.startsWith(primary.path + File.separator)) {
            "Недопустимый путь каталога"
        }
        return current
    }

    /** Fail closed for symlinks, unknown files, oversized trees and unsupported media types.
     * A .nomedia marker affects descendants as well, hence traversal is recursive. */
    fun listMedia(raw: String): List<NomediaSafEntry> {
        val root = folder(raw)
        val found = ArrayList<NomediaSafEntry>()
        var visited = 0
        fun visit(dir: File, depth: Int) {
            check(depth <= 12) { "Слишком глубокий каталог" }
            val children = checkNotNull(dir.listFiles()) { "Не удаётся прочитать полный состав папки" }
            for (child in children) {
                check(++visited <= 10_000) { "Каталог слишком велик для безопасной проверки" }
                if (child == File(root, ".nomedia")) continue
                val path = child.toPath()
                check(!Files.isSymbolicLink(path)) { "Символьные ссылки запрещены" }
                when {
                    Files.isDirectory(path, NOFOLLOW_LINKS) -> visit(child, depth + 1)
                    Files.isRegularFile(path, NOFOLLOW_LINKS) -> {
                        check(depth == 0) { "Вложенные медиа требуют отдельной проверки" }
                        val extension = child.extension.lowercase(java.util.Locale.ROOT)
                        val mime = mimeForExtension(extension)
                        val mediaMime=mime!=null&&(mime.startsWith("image/")||
                            mime.startsWith("video/")||mime.startsWith("audio/"))
                        if(!mediaMime){
                            // No names, paths, URI, file content or unknown arbitrary metadata.
                            val safeExtension=extension.takeIf{it.matches(Regex("[a-z0-9]{1,12}"))}?:
                                if(extension.isEmpty())"none" else "other"
                            val safeMime=mime?.takeIf{it.matches(Regex("[a-zA-Z0-9.+-]{1,40}/[a-zA-Z0-9.+-]{1,60}"))}?:"unknown"
                            AppLog.w("FolderVisibility","INVENTORY_REJECTED reason=unsupported_mime extension=$safeExtension mime=$safeMime")
                        }
                        check(mediaMime) {
                            "В папке есть файлы, которые нельзя безопасно отнести к медиа"
                        }
                        check(found.size < 2048) { "Слишком много файлов для проверки" }
                        val rel = child.relativeTo(root).invariantSeparatorsPath
                        found += NomediaSafEntry(rel, child.name, checkNotNull(mime), Files.size(path))
                    }
                    else -> error("Неподдерживаемый элемент каталога")
                }
            }
        }
        visit(root, 0)
        return found
    }

    override suspend fun inspect(treeUri: String): NomediaMarkerProof? = withContext(Dispatchers.IO) {
        val path = File(folder(treeUri), ".nomedia").toPath()
        if (Files.notExists(path, NOFOLLOW_LINKS)) return@withContext null
        check(Files.isRegularFile(path, NOFOLLOW_LINKS)) { "Служебный маркер изменён" }
        val size = Files.size(path)
        // Never read unbounded foreign marker bytes.
        val owner = if (size == (prefix.length + 36).toLong()) {
            val bytes = Files.readAllBytes(path)
            val value = bytes.toString(Charsets.US_ASCII)
            val token = value.removePrefix(prefix)
            if (value.startsWith(prefix) &&
                runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false))
                idPrefix + token else null
        } else null
        NomediaMarkerProof(owner ?: "foreign-local", size)
    }

    override suspend fun createEmpty(treeUri: String): NomediaMarkerProof = withContext(Dispatchers.IO) {
        val planned = checkNotNull(expected) { "Не предоставлен проверенный физический состав" }
        val before = listMedia(treeUri).map { NomediaMediaSignature(it.displayName, it.byteCount ?: -1L) }
        check(nomediaInventoryMatches(planned, before)) {
            "Состав папки изменился перед созданием маркера. Файлы не затронуты"
        }
        check(inspect(treeUri) == null) { ".nomedia уже существует" }
        val target = File(folder(treeUri), ".nomedia").toPath()
        val token = UUID.randomUUID().toString()
        val bytes = (prefix + token).toByteArray(Charsets.US_ASCII)
        // Keep CREATE_NEW and the write on one no-follow file descriptor.
        // Reopening a just-created pathname could follow a concurrently replaced symlink.
        FileChannel.open(target,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,NOFOLLOW_LINKS)
            .use { channel ->
                val buffer=ByteBuffer.wrap(bytes)
                while(buffer.hasRemaining()) check(channel.write(buffer)>0)
                channel.force(true)
            }
        val proof = checkNotNull(inspect(treeUri)) { "Служебный маркер не обнаружен" }
        check(proof.documentId == idPrefix + token && proof.bytes == bytes.size.toLong()) {
            "Созданный маркер не подтверждён"
        }
        proof
    }

    override suspend fun deleteVerified(treeUri: String, documentId: String): Boolean =
        withContext(Dispatchers.IO) {
            check(documentId.startsWith(idPrefix)) { "Чужой .nomedia удалять нельзя" }
            val observed = checkNotNull(inspect(treeUri)) { "Служебный маркер отсутствует" }
            check(observed.documentId == documentId && observed.bytes == (prefix.length + 36).toLong()) {
                "Служебный маркер заменён. Удаление запрещено"
            }
            Files.delete(File(folder(treeUri), ".nomedia").toPath())
            true
        }

    fun localMediaUri(treeUri:String, documentId:String):Uri {
        val root=folder(treeUri)
        val entry=checkNotNull(listMedia(treeUri).singleOrNull { it.documentId==documentId }) {
            "Медиа в каталоге не найдено"
        }
        val file=File(root,entry.documentId)
        check(file.canonicalPath.startsWith(root.canonicalPath + File.separator) &&
            Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS))
        return Uri.fromFile(file)
    }
}
