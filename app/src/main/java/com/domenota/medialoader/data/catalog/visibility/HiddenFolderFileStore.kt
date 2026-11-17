package com.domenota.medialoader.data.catalog.visibility

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest
import com.domenota.medialoader.core.logging.AppLog
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Non-cache storage, deliberately separate from the vault. One random operation
 * directory per file keeps equal display names distinct and recovery exact. */
internal class HiddenFolderFileStore(root: File) {
    private val base = root.absoluteFile
    private fun root(): File {
        check(base.canonicalFile == base) { "Некорректный путь скрытого хранилища" }
        Files.createDirectories(base.toPath())
        check(Files.isDirectory(base.toPath(), NOFOLLOW_LINKS))
        val owner = File(base, OWNER_FILE)
        if (!Files.exists(owner.toPath(), NOFOLLOW_LINKS)) {
            // Never put .nomedia into a pre-existing directory with unrelated files.
            check(base.listFiles()?.isEmpty() == true) { "Выбранное скрытое хранилище уже используется" }
            // CREATE_NEW is essential: an external writer may create the marker
            // after the empty-directory check. Never truncate or follow its file.
            java.nio.channels.FileChannel.open(owner.toPath(), java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(OWNER_BYTES)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
        }
        verifyOwner() // Critical: refuse a foreign/symlink owner BEFORE creating .nomedia.
        val marker = File(base, ".nomedia")
        if (!Files.exists(marker.toPath(), NOFOLLOW_LINKS)) {
            try { Files.createFile(marker.toPath()) }
            catch (_: java.nio.file.FileAlreadyExistsException) { }
        }
        verifyNomedia()
        return base
    }

    private fun verifyOwner() {
        val owner = File(base, OWNER_FILE)
        val ownerVerified = Files.isRegularFile(owner.toPath(), NOFOLLOW_LINKS) &&
            owner.length() == OWNER_BYTES.size.toLong() && owner.readBytes().contentEquals(OWNER_BYTES)
        if (!ownerVerified) AppLog.w("FolderVisibility", "ROOT_GUARD code=OWNER_UNVERIFIED stage=ROOT ownerVerified=false")
        check(ownerVerified) { "Не подтверждена принадлежность скрытого хранилища ML" }
    }

    private fun verifyNomedia(): HiddenNomediaKind {
        val marker = File(base, ".nomedia")
        val kind = HiddenNomediaPolicy.classify(marker, base)
        if (kind == null) {
            val observation = HiddenRootDiagnostic.observeNomedia(marker)
            AppLog.w("FolderVisibility", "ROOT_GUARD code=NOMEDIA_${observation.code} stage=ROOT ownerVerified=true")
            if (AppLog.isExtended()) AppLog.d("FolderVisibility", "ROOT_DETAIL ${observation.extendedSummary()} ownerVerified=true")
        }
        check(kind != null) { "Не удалось подтвердить .nomedia" }
        return kind
    }

    /** Recovery-only preflight. No mkdir, marker creation, journal write or
     * cache invalidation before the existing destination is accepted. */
    fun verifyExistingRoot(): HiddenNomediaKind {
        check(base.canonicalFile == base) { "Некорректный путь скрытого хранилища" }
        check(Files.isDirectory(base.toPath(), NOFOLLOW_LINKS)) { "Скрытое хранилище недоступно" }
        verifyOwner()
        return verifyNomedia()
    }

    fun ensureRoot(): File = root()

    fun target(token: String, name: String): File {
        require(token.matches(Regex("[A-Za-z0-9_-]{16,100}")))
        require(name.isNotBlank() && !name.startsWith('.') && name.none { it == '/' || it == '\\' || it.isISOControl() })
        val root = root()
        val directory = File(root, token)
        check(directory.canonicalFile == directory && directory.parentFile == root)
        Files.createDirectories(directory.toPath())
        check(Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS))
        val result = File(directory, name)
        check(result.canonicalFile == result && result.parentFile == directory)
        return result
    }

    /** Checks an existing locator without creating its parent or following links. */
    fun owns(file: File): Boolean = runCatching {
        val candidate = file.absoluteFile
        val parent = candidate.parentFile ?: return@runCatching false
        candidate == candidate.canonicalFile && parent.parentFile == base &&
            base == base.canonicalFile && File(base,OWNER_FILE).let { owner ->
                Files.isRegularFile(owner.toPath(), NOFOLLOW_LINKS) && owner.length() == OWNER_BYTES.size.toLong() && owner.readBytes().contentEquals(OWNER_BYTES)
            } && parent.name.matches(Regex("[A-Za-z0-9_-]{16,100}")) &&
            !candidate.name.startsWith('.') && candidate.name.none { it.isISOControl() } &&
            Files.isDirectory(parent.toPath(), NOFOLLOW_LINKS)
    }.getOrDefault(false)

    /** Read-only check for explicit folder browsing; never repair a missing
     * marker or create storage as a side effect of a catalog scan. */
    fun matchesReceipt(file: File, record: VisibilityMoveRecord): Boolean = runCatching {
        record.direction == VisibilityDirection.HIDE && owns(file) &&
            file.parentFile?.name == record.destinationToken && file.name == record.sourceName &&
            Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) && file.length() == record.fingerprint.bytes &&
            (record.sourceModifiedMillis == null || file.lastModified() == record.sourceModifiedMillis) &&
            File(base,".nomedia").let { HiddenNomediaPolicy.classify(it,base) != null }
    }.getOrDefault(false)

    suspend fun copyVerified(target: File, expected: VisibilityFingerprint, modifiedMillis: Long?, open: () -> InputStream) {
        check(owns(target))
        root() // Reconfirm exclusion before writing media.
        if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
            check(Files.isRegularFile(target.toPath(), NOFOLLOW_LINKS) && fingerprint(target.inputStream()) == expected) {
                "Назначение занято другими данными"
            }
            confirmModified(target, modifiedMillis)
            return
        }
        val stage = Files.createTempFile(target.parentFile.toPath(), ".visibility-", ".tmp").toFile()
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            open().use { input -> FileOutputStream(stage).use { out ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    check(n.toLong() <= expected.bytes - bytes) { "Исходный файл увеличился" }
                    out.write(buffer, 0, n); digest.update(buffer, 0, n); bytes += n
                }
                out.flush(); out.fd.sync()
            } }
            check(VisibilityFingerprint(bytes, digest.hex()) == expected && fingerprint(stage.inputStream()) == expected)
            confirmModified(stage, modifiedMillis)
            currentCoroutineContext().ensureActive()
            // No REPLACE_EXISTING. The operation gate serializes all app writers;
            // the token directory is app-owned and never exposed as a picker target.
            check(owns(target) && !Files.exists(target.toPath(), NOFOLLOW_LINKS))
            Files.move(stage.toPath(), target.toPath())
            check(fingerprint(target.inputStream()) == expected)
            confirmModified(target, modifiedMillis)
        } finally { Files.deleteIfExists(stage.toPath()) }
    }

    fun verifyLocation(file: File, modifiedMillis: Long?) {
        check(owns(file) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        root()
        check(modifiedMillis == null || file.lastModified() == modifiedMillis) { "Дата скрытого файла изменилась" }
    }

    fun removeVerified(file: File) {
        check(owns(file) && Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS))
        Files.delete(file.toPath())
        check(Files.notExists(file.toPath(), NOFOLLOW_LINKS))
    }

    private fun confirmModified(file: File, millis: Long?) {
        if (millis == null) return
        check(file.lastModified() == millis || file.setLastModified(millis)) { "Не удалось сохранить дату файла" }
        check(file.lastModified() == millis) { "Дата файла не совпала" }
    }

    companion object {
        private const val OWNER_FILE = ".ml-folder-visibility-v1"
        private val OWNER_BYTES = "MediaLoader ordinary folder visibility v1\n".toByteArray(Charsets.UTF_8)
        suspend fun fingerprint(stream: InputStream): VisibilityFingerprint = stream.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var bytes = 0L
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                if (n > 0) { digest.update(buffer, 0, n); bytes = Math.addExact(bytes, n.toLong()) }
            }
            VisibilityFingerprint(bytes, digest.hex())
        }
        private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
