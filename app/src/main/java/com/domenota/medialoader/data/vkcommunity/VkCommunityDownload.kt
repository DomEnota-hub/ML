package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.domenota.medialoader.data.storage.StorageManager
import com.domenota.medialoader.data.storage.StorageSettings
import com.domenota.medialoader.data.MediaRepository
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Streams into a private temp file and checks exact bytes before publishing. */
class VkCommunityDownload(private val context: Context, private val db: VkCommunityDatabase) {
    private val storage = StorageManager(context, StorageSettings(context))

    suspend fun download(item: VkStoredImage): VkItemPhase = withContext(Dispatchers.IO) {
        val candidate = item.candidate
        // VK identity can eliminate a known duplicate before transferring any bytes.
        db.savedPhotoUri(candidate.photoKey)?.let { previous ->
            if (isPresent(previous)) {
                db.itemPhase(item.jobId, candidate.photoKey, VkItemPhase.DUPLICATE,
                    savedUri = previous)
                return@withContext VkItemPhase.DUPLICATE
            }
        }
        val temp = File.createTempFile("vk-community-", ".partial", context.cacheDir)
        db.itemPhase(item.jobId, candidate.photoKey, VkItemPhase.DOWNLOADING)
        try {
            val sha = transfer(candidate.url, temp)
            val format = StaticImageProbe.detect(temp)
                ?: throw IOException("Файл не является статичным JPG, PNG или WebP")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.path, options)
            if (options.outWidth <= 0 || options.outHeight <= 0)
                throw IOException("Изображение повреждено")
            publishLock.withLock {
                val alreadyById = db.savedPhotoUri(candidate.photoKey)
                if (alreadyById != null && isPresent(alreadyById)) {
                    db.itemPhase(item.jobId, candidate.photoKey, VkItemPhase.DUPLICATE,
                        sha, alreadyById)
                    return@withContext VkItemPhase.DUPLICATE
                }
                val alreadyByHash = db.hashUri(sha)
                if (alreadyByHash != null) {
                    if (isPresent(alreadyByHash)) {
                        db.itemPhase(item.jobId, candidate.photoKey, VkItemPhase.DUPLICATE,
                            sha, alreadyByHash)
                        return@withContext VkItemPhase.DUPLICATE
                    }
                    db.removeHash(sha)
                }
                currentCoroutineContext().ensureActive()
                val name = "VK_${candidate.postKey}_${candidate.photoKey}.${format.extension}"
                    .replace(Regex("[^A-Za-z0-9_.-]"), "_")
                val published = storage.publish(temp, name, format.mime)
                try {
                    db.putHash(sha, published.uri.toString(), candidate.photoKey)
                    db.itemPhase(item.jobId, candidate.photoKey, VkItemPhase.DONE,
                        sha, published.uri.toString())
                } catch (error: Exception) {
                    runCatching { context.contentResolver.delete(published.uri, null, null) }
                    throw error
                }
                // A history write is idempotent and cannot roll back an already saved file.
                runCatching {
                    MediaRepository.get(context).recordVkCommunityImage(
                        item.jobId, candidate.photoKey, candidate.postKey,
                        published.fileName, published.uri.toString(), temp.length(),
                    )
                }
                VkItemPhase.DONE
            }
        } catch (error: Exception) {
            db.itemPhase(item.jobId, candidate.photoKey, VkItemPhase.ERROR,
                error = safeMessage(error))
            if (error is VkApiFailure && error.kind in setOf(
                    VkApiFailure.Kind.RATE_LIMIT, VkApiFailure.Kind.SERVER)) throw error
            VkItemPhase.ERROR
        } finally {
            temp.delete()
        }
    }

    private fun isPresent(savedUri: String): Boolean = runCatching {
        context.contentResolver.openInputStream(Uri.parse(savedUri))?.use { it.read() >= 0 } == true
    }.getOrDefault(false)

    private fun safeMessage(error: Exception): String {
        if (error is VkApiFailure) return error.userMessage()
        val known = setOf("Файл не является статичным JPG, PNG или WebP", "Изображение повреждено",
            "Недопустимый адрес изображения", "Пустое перенаправление изображения",
            "Слишком много перенаправлений изображения", "Изображение слишком большое",
            "Получен пустой файл")
        val text = error.message.orEmpty()
        return when {
            text in known -> text
            text.matches(Regex("Сервер изображения ответил HTTP [0-9]{3}")) -> text
            else -> "Ошибка сети при загрузке изображения"
        }
    }

    private suspend fun transfer(address: String, target: File): String {
        var current = address
        repeat(4) {
            currentCoroutineContext().ensureActive()
            val uri = runCatching { URI(current) }.getOrNull()
            if (uri?.scheme != "https" || uri.host == null || uri.userInfo != null ||
                uri.port !in listOf(-1, 443)) throw IOException("Недопустимый адрес изображения")
            val connection = URL(current).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 20_000
                connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp")
                val status = connection.responseCode
                if (status in 300..399) {
                    current = connection.getHeaderField("Location")?.let { uri.resolve(it).toString() }
                        ?: throw IOException("Пустое перенаправление изображения")
                    return@repeat
                }
                if (status == 429) throw VkApiFailure(VkApiFailure.Kind.RATE_LIMIT)
                if (status in 500..599) throw VkApiFailure(VkApiFailure.Kind.SERVER)
                if (status !in 200..299) throw IOException("Сервер изображения ответил HTTP $status")
                if (connection.contentLengthLong > MAX_BYTES) throw IOException("Изображение слишком большое")
                val hash = MessageDigest.getInstance("SHA-256")
                target.outputStream().use { output -> connection.inputStream.use { input ->
                    val buffer = ByteArray(32_768)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("Изображение слишком большое")
                        hash.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                    if (total == 0L) throw IOException("Получен пустой файл")
                } }
                return hash.digest().joinToString("") { "%02x".format(it) }
            } finally { connection.disconnect() }
        }
        throw IOException("Слишком много перенаправлений изображения")
    }

    private companion object {
        const val MAX_BYTES = 25_000_000L
        val publishLock = Mutex()
    }
}
