package com.domenota.medialoader.data.preview

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.provider.InstagramProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** One bounded cache for publication covers. UI receives only decoded images. */
object RemoteImageLoader {
    private val cache = object : LruCache<String, Bitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    // Repeated thumbnails should not flood the user's diagnostic log.
    private val recentErrors = ConcurrentHashMap<String, Long>()
    private val retryPolicy = ThumbnailRetryPolicy()
    private val fetchLocks = Array(32) { Any() }

    fun fetch(context: Context, url: String, sourceHint: String? = null,
        maxDimension: Int = 1024): Bitmap? =
        synchronized(fetchLocks[(url.hashCode() and Int.MAX_VALUE) % fetchLocks.size]) {
            fetchLocked(context, url, sourceHint, maxDimension.coerceIn(256, 2048))
        }

    private fun fetchLocked(context: Context, url: String, sourceHint: String?,
        maxDimension: Int): Bitmap? {
        val cacheKey = "$maxDimension:$url"
        cache.get(cacheKey)?.let { return it }
        val diskFile = localFile(context, url)
        if (diskFile.isFile) {
            decodeSampled(diskFile.readBytes(), maxDimension)?.let { bitmap ->
                cacheBitmap(cacheKey, bitmap)
                diskFile.setLastModified(System.currentTimeMillis())
                return bitmap
            }
            diskFile.delete()
        }
        if (!retryPolicy.canAttempt(url)) return null
        val host = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: "invalid"
        val source = previewSource(url, sourceHint) ?: run {
            report(host, "Thumbnail URL host not supported")
            return null
        }
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (error: Exception) {
            retryPolicy.failed(url, null)
            report(host, "Thumbnail connection setup failed: ${error.javaClass.simpleName}")
            return null
        }
        try {
            connection.instanceFollowRedirects = !source.requireVkImage
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("Referer", source.referer)
            connection.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36",
            )
            connection.setRequestProperty(
                "Accept", "image/avif,image/webp,image/apng,image/*;q=0.8,*/*;q=0.5",
            )
            val status = connection.responseCode
            AppLog.verbose("Thumbnail", "Fetch · source=${sourceHint ?: "default"} · host=$host · status=$status · contentType=${connection.contentType.orEmpty().take(40)}")
            if (status !in 200..299) {
                retryPolicy.failed(url, status)
                report(host, "Thumbnail HTTP $status")
                return null
            }
            if (source.requireVkImage && !VkThumbnailPolicy.isAllowedUrl(connection.url.toString())) {
                retryPolicy.failed(url, status)
                report(host, "Thumbnail redirected outside HTTPS VK image hosts")
                return null
            }
            if (connection.contentLengthLong > MAX_IMAGE_BYTES) {
                retryPolicy.failed(url, status)
                report(host, "Thumbnail exceeded size limit")
                return null
            }
            val mime = connection.contentType.orEmpty().lowercase(Locale.ROOT)
            if ((source.requireVkImage && !VkThumbnailPolicy.isImageContentType(mime)) ||
                mime.startsWith("text/html") || mime.startsWith("application/json")) {
                retryPolicy.failed(url, status)
                report(host, "Thumbnail returned a non-image response")
                return null
            }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16_384)
                while (output.size() <= MAX_IMAGE_BYTES) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.size > MAX_IMAGE_BYTES) {
                retryPolicy.failed(url, status)
                report(host, "Thumbnail exceeded size limit while streaming")
                return null
            }
            return decodeSampled(bytes, maxDimension)?.also { bitmap ->
                retryPolicy.succeeded(url)
                cacheBitmap(cacheKey, bitmap)
                // Even small verified artwork should survive loss of an expiring CDN URL.
                if (bitmap.width > 0 && bitmap.height > 0) runCatching {
                    diskFile.parentFile?.mkdirs()
                    val temporary = File(diskFile.parentFile, diskFile.name + ".tmp")
                    temporary.writeBytes(bytes)
                    if (!temporary.renameTo(diskFile)) temporary.delete()
                    pruneDiskCache(diskFile.parentFile)
                }
            } ?: run {
                retryPolicy.failed(url, status)
                report(host, "Thumbnail decode failed (MIME=${mime.take(40)})")
                null
            }
        } catch (error: Exception) {
            retryPolicy.failed(url, null)
            report(host, "Thumbnail network error: ${error.javaClass.simpleName}")
            return null
        } finally {
            connection.disconnect()
        }
    }

    /** Persist validated image bytes; raw URLs and signing parameters never become filenames. */
    private fun localFile(context: Context, url: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, "remote-artwork"), "$hash.img")
    }

    private fun pruneDiskCache(dir: File?) {
        val files = dir?.listFiles()?.filter { it.isFile && it.extension == "img" } ?: return
        var total = files.sumOf { it.length() }
        if (total <= 48L * 1024 * 1024) return
        for (entry in files.sortedBy { it.lastModified() }) {
            if (total <= 40L * 1024 * 1024) break
            total -= entry.length()
            entry.delete()
        }
    }

    private fun cacheBitmap(key: String, bitmap: Bitmap) {
        if (bitmap.byteCount <= 4 * 1024 * 1024) cache.put(key, bitmap)
    }

    private fun decodeSampled(bytes: ByteArray, maxDimension: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimension) sample *= 2
        return BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample },
        )
    }

    private fun report(host: String, reason: String) {
        val now = System.currentTimeMillis()
        val key = "$host|$reason"
        val previous = recentErrors[key]
        if (previous != null && now - previous < 60_000L) return
        if (recentErrors.size > 50) recentErrors.clear()
        recentErrors[key] = now
        AppLog.w("Thumbnail", "Image host=$host · $reason")
    }

    private fun previewSource(url: String, sourceHint: String? = null): PreviewSource? {
        if (InstagramProvider.safeMediaUrl(url)) return PreviewSource("https://www.instagram.com/")
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true)) return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        // Shazam's confirmed artwork is also shown in history, where sourceHint
        // is absent. Accept only well-known HTTPS artwork hosts, never arbitrary URLs.
        if (MusicArtworkHostPolicy.allowed(url)) {
            return PreviewSource("https://www.shazam.com/")
        }
        if (sourceHint == "hitmoz") {
            val musicHost = (host == "avatars.yandex.net") || listOf("hitmoz.org", "hitmoz.com", "hitmcdn.com",
                "deliciousoranges.com", "deliciouspeaches.com").any {
                host == it || host.endsWith(".$it")
            }
            if (musicHost && uri.port in listOf(-1, 443) && uri.userInfo == null) {
                return PreviewSource("https://ru.hitmoz.org/")
            }
        }
        val youtubeImage = host == "i.ytimg.com" || host.endsWith(".ytimg.com") ||
            host == "i9.ytimg.com" || host.endsWith(".googleusercontent.com")
        val rutubeImage = host == "rutubelist.ru" || host.endsWith(".rutubelist.ru") ||
            host == "rutube.ru" || host.endsWith(".rutube.ru") ||
            host == "rtbcdn.ru" || host.endsWith(".rtbcdn.ru")
        return when {
            youtubeImage -> PreviewSource("https://www.youtube.com/")
            rutubeImage -> PreviewSource("https://rutube.ru/")
            VkThumbnailPolicy.isAllowedUrl(url) -> PreviewSource("https://vk.com/", requireVkImage = true)
            else -> null
        }
    }

    private data class PreviewSource(val referer: String, val requireVkImage: Boolean = false)

    private const val MAX_IMAGE_BYTES = 8_000_000
}
