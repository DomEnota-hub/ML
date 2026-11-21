package com.domenota.medialoader.data.catalog.visibility

import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE

/**
 * An app-owned child of the ORIGINAL physical directory. Its own .nomedia
 * excludes only media moved to this unique child, never the other 800 siblings.
 * No media bytes are copied or deleted by this class.
 */
internal class SelectiveArchiveLocalTarget(
    private val root:()->File={ Environment.getExternalStorageDirectory().canonicalFile },
    private val permitted:()->Boolean={ FolderVisibilityStorageAccess.granted() },
) {
    private val ownerName=".ml-selective-owner-v1"
    private val markerName=".nomedia"

    internal fun relative(original:SelectiveArchiveIdentity,token:String):String {
        require(original.wellFormed() && token.matches(Regex("[A-Za-z0-9_-]{24,64}")))
        return original.relativePath+"MLSelectiveArchive_$token/"
    }

    /** Validate every direct child without following symlinks. */
    private fun directory(relative:String,createLeaf:Boolean):File {
        check(permitted()) { "Нет подтверждённого разрешения на управление файлами" }
        check(relative.endsWith("/") && !relative.startsWith("/"))
        val parent=root().canonicalFile
        check(Files.isDirectory(parent.toPath(),NOFOLLOW_LINKS))
        var next=parent
        val parts=relative.removeSuffix("/").split('/')
        for((index,part) in parts.withIndex()) {
            check(part.isNotBlank() && part !in setOf(".","..") && '\\' !in part)
            val child=File(next,part)
            if(index==parts.lastIndex && createLeaf && Files.notExists(child.toPath(),NOFOLLOW_LINKS))
                Files.createDirectory(child.toPath())
            check(Files.isDirectory(child.toPath(),NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(child.toPath())) {
                "Нельзя доверять каталогу выборочного архива"
            }
            next=child
        }
        check(next.toPath().startsWith(parent.toPath()) && next!=parent)
        return next
    }

    private fun checkedFolder(original:SelectiveArchiveIdentity,r:SelectiveArchiveMoveReceipt,
        createLeaf:Boolean):File {
        check(r.originalUri==original.originalUri &&
            r.catalogId==original.catalogId && r.originalSha256==original.sha256 &&
            r.targetRelativePath==relative(original,r.token)) {
            "Неверная привязка каталога к исходному медиа"
        }
        return directory(r.targetRelativePath,createLeaf)
    }

    private fun bytes(token:String) = "ML-SELECTIVE-ARCHIVE-V1:$token\n".toByteArray(Charsets.US_ASCII)

    private fun writeNew(file:File,content:ByteArray) {
        FileChannel.open(file.toPath(),CREATE_NEW,WRITE,NOFOLLOW_LINKS).use { channel ->
            val data=ByteBuffer.wrap(content)
            while(data.hasRemaining()) check(channel.write(data)>0)
            channel.force(true)
        }
    }

    private fun owned(folder:File,token:String):Boolean {
        val owner=File(folder,ownerName)
        val marker=File(folder,markerName)
        return Files.isRegularFile(owner.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(owner.toPath()) &&
            Files.size(owner.toPath())==bytes(token).size.toLong() &&
            Files.readAllBytes(owner.toPath()).contentEquals(bytes(token)) &&
            Files.isRegularFile(marker.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(marker.toPath()) && Files.size(marker.toPath())==0L
    }

    /** Must not consider a foreign file, directory or symlink part of ours. */
    fun inspect(original:SelectiveArchiveIdentity,r:SelectiveArchiveMoveReceipt):Boolean {
        val dir=runCatching { checkedFolder(original,r,false) }.getOrNull() ?: return false
        if(!owned(dir,r.token))return false
        val children=dir.listFiles() ?: return false
        return children.all { file ->
            when(file.name) {
                ownerName,markerName -> Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(file.toPath())
                original.displayName -> Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) &&
                    !Files.isSymbolicLink(file.toPath())
                else -> false
            }
        }
    }

    fun ensure(original:SelectiveArchiveIdentity,r:SelectiveArchiveMoveReceipt) {
        val dir=checkedFolder(original,r,true)
        val owner=File(dir,ownerName)
        val marker=File(dir,markerName)
        if(Files.notExists(owner.toPath(),NOFOLLOW_LINKS)) {
            // An unknown/pre-existing directory is never silently adopted.
            check(dir.listFiles()?.isEmpty()==true) {
                "Целевой каталог уже содержит неизвестные данные"
            }
            writeNew(owner,bytes(r.token))
        }
        check(Files.isRegularFile(owner.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(owner.toPath()) &&
            Files.size(owner.toPath())==bytes(r.token).size.toLong() &&
            Files.readAllBytes(owner.toPath()).contentEquals(bytes(r.token))) {
            "Чужой или изменённый владелец каталога"
        }
        if(Files.notExists(marker.toPath(),NOFOLLOW_LINKS)) {
            check(dir.listFiles()?.all { it.name==ownerName }==true) {
                "Появился посторонний файл; .nomedia не создаётся"
            }
            writeNew(marker,byteArrayOf())
        }
        check(inspect(original,r)) { "Не подтверждён собственный .nomedia" }
    }

    /** Validate a destination's parent without requiring or creating a file.
     * Used for collision preflight before MediaStore's atomic row path update. */
    /** Remove ONLY two ML-owned marker files from an otherwise empty child
     * after exact original restoration. Never recurse or remove any media. */
    fun releaseEmpty(original:SelectiveArchiveIdentity,
        receipt:SelectiveArchiveMoveReceipt):Boolean {
        val dir=runCatching { checkedFolder(original,receipt,false) }.getOrNull()
            ?: return true // Already removed, including interrupted cleanup.
        val marker=File(dir,markerName)
        val owner=File(dir,ownerName)
        // An earlier interruption may have removed just .nomedia. The owner
        // must STILL match before touching anything.
        val ownerVerified=Files.isRegularFile(owner.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(owner.toPath()) &&
            Files.size(owner.toPath())==bytes(receipt.token).size.toLong() &&
            Files.readAllBytes(owner.toPath()).contentEquals(bytes(receipt.token))
        check(ownerVerified) { "Чужой владелец каталога: очистка запрещена" }
        check(dir.listFiles()?.all { it.name in setOf(markerName,ownerName) }==true) {
            "В скрытом каталоге остались данные; удаление запрещено"
        }
        if(Files.exists(marker.toPath(),NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(marker.toPath(),NOFOLLOW_LINKS) &&
                !Files.isSymbolicLink(marker.toPath()) &&
                Files.size(marker.toPath())==0L) { "Неизвестный .nomedia" }
            Files.delete(marker.toPath())
        }
        // Verify again after deleting marker, before removing owned tag.
        check(dir.listFiles()?.map { it.name }==listOf(ownerName) &&
            Files.readAllBytes(owner.toPath()).contentEquals(bytes(receipt.token)))
        Files.delete(owner.toPath())
        Files.delete(dir.toPath()) // Must be empty, never recursive.
        return true
    }

    fun sourceFileIfAbsent(original:SelectiveArchiveIdentity):File =
        File(directory(original.relativePath,false),original.displayName)

    fun hiddenFileIfAbsent(original:SelectiveArchiveIdentity,
        receipt:SelectiveArchiveMoveReceipt):File {
        check(inspect(original,receipt)) { "Не подтверждено собственное скрытое хранилище" }
        return File(checkedFolder(original,receipt,false),original.displayName)
    }

    fun sourceFile(original:SelectiveArchiveIdentity):File {
        val path=directory(original.relativePath,false)
        val file=File(path,original.displayName)
        check(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath())) {
            "Исходный файл отсутствует или изменён"
        }
        return file
    }

    fun hiddenFile(original:SelectiveArchiveIdentity,r:SelectiveArchiveMoveReceipt):File {
        check(inspect(original,r)) { "Неизвестное состояние скрытого каталога" }
        val file=File(checkedFolder(original,r,false),original.displayName)
        check(Files.isRegularFile(file.toPath(),NOFOLLOW_LINKS) &&
            !Files.isSymbolicLink(file.toPath()))
        return file
    }
}
