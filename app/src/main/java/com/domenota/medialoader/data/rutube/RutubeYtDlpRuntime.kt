package com.domenota.medialoader.data.rutube

import android.content.Context
import android.media.MediaMetadataRetriever
import android.webkit.CookieManager
import com.domenota.medialoader.core.diagnostics.AccountBrowserPolicy
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.provider.ExtractionException
import com.domenota.medialoader.core.provider.RutubeProvider
import com.domenota.medialoader.core.provider.StreamExtractor
import com.domenota.medialoader.core.provider.StreamInfo
import com.domenota.medialoader.core.provider.YtDlpJson
import com.domenota.medialoader.data.download.DownloadFailure
import com.domenota.medialoader.data.youtube.YtDlpDownloader
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONException

/** RUTUBE yt-dlp adapter. Public access is tried first; a WebView session is used when present. */
class RutubeYtDlpRuntime(context: Context) : StreamExtractor, YtDlpDownloader {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    @Volatile private var ready = false
    private val cookiesFile get() = File(appContext.filesDir, "rutube-session.txt")

    val hasSession: Boolean get() = cookiesFile.exists() && cookiesFile.length() > 0L

    init {
        AppLog.init(appContext)
    }

    /**
     * Captures a completed RUTUBE WebView login. The user never handles a cookie file manually;
     * this private Netscape jar exists only because yt-dlp consumes browser sessions in that form.
     */
    fun saveWebSession(
        rutubeCookies: String?,
        wwwCookies: String?,
        studioCookies: String?,
    ): Boolean {
        val values = linkedMapOf<Pair<String, String>, String>()
        collectCookieHeader(".rutube.ru", rutubeCookies, values)
        collectCookieHeader(".rutube.ru", wwwCookies, values)
        collectCookieHeader(".studio.rutube.ru", studioCookies, values)

        val authenticated = values.keys.any { (_, name) ->
            AUTH_COOKIE_NAMES.any { it.equals(name, ignoreCase = true) }
        }
        if (!authenticated) return false

        val temporary = File(appContext.filesDir, "rutube-session.tmp")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("# Netscape HTTP Cookie File")
                writer.appendLine("# Generated locally by MediaLoader from the user's RUTUBE WebView session.")
                values.forEach { (key, value) ->
                    val (domain, name) = key
                    writer.append(domain).append('\t')
                        .append("TRUE\t/\tTRUE\t0\t")
                        .append(name).append('\t').append(value).appendLine()
                }
            }
            require(temporary.length() in 1..MAX_COOKIE_BYTES) { "Сессия RUTUBE слишком большая" }
            if (cookiesFile.exists()) require(cookiesFile.delete()) { "Не удалось заменить сессию RUTUBE" }
            require(temporary.renameTo(cookiesFile)) { "Не удалось сохранить сессию RUTUBE" }
            AppLog.i("RutubeAuth", "Web session saved · entries=${values.size}")
            return true
        } catch (error: Exception) {
            AppLog.e("RutubeAuth", "Web session save failed: ${error.message}", error)
            return false
        } finally {
            temporary.delete()
        }
    }

    fun clearSession() {
        discardUnverifiedSession()
        val manager = CookieManager.getInstance()
        listOf("rutube.ru", "www.rutube.ru", "studio.rutube.ru", "gid.ru", "auth.gid.ru")
            .forEach { host ->
                val url = "https://$host/"
                AccountBrowserPolicy.rutubeCookieNames(manager.getCookie(url)).forEach { name ->
                    manager.setCookie(url, "$name=; Max-Age=0; Path=/; Secure")
                    val root = if (host.endsWith("gid.ru")) "gid.ru" else "rutube.ru"
                    manager.setCookie(url, "$name=; Max-Age=0; Domain=.$root; Path=/; Secure")
                }
            }
        manager.flush()
        AppLog.i("RutubeAuth", "RUTUBE session removed")
    }

    fun discardUnverifiedSession() { cookiesFile.delete() }

    override suspend fun extract(url: String): StreamInfo {
        ensureReady()
        AppLog.i("RUTUBE", "Analyze start · session=$hasSession · url=$url")
        val output = try {
            val request = YoutubeDLRequest(url).apply {
                addOption("-J")
                addOption("--no-playlist")
                addOption("--no-warnings")
                if (hasSession) addOption("--cookies", cookiesFile.absolutePath)
            }
            withContext(Dispatchers.IO) { YoutubeDL.getInstance().execute(request).out }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            AppLog.e("RUTUBE", "Analyze failed: ${error.message}", error)
            throw toExtractionException(error)
        } catch (error: Exception) {
            AppLog.e("RUTUBE", "Analyze failed: ${error.message}", error)
            throw ExtractionException("Не удалось получить данные видео RUTUBE.", cause = error)
        }

        return try {
            YtDlpJson.parse(output.trim()).also { info ->
                val thumbHost = info.thumbnailUrl?.let {
                    runCatching { java.net.URI(it).host }.getOrNull()
                }
                AppLog.i(
                    "RUTUBE",
                    "Analyze success · id=${info.id} · formats=${info.formats.size} · " +
                        "thumbnail=${info.thumbnailUrl != null} · thumbnailHost=${thumbHost ?: "none"}",
                )
                val dimensions = info.formats.asSequence()
                    .filter { it.hasVideo }
                    .map { "${it.width ?: "?"}x${it.height ?: "?"}" }
                    .distinct().sorted().joinToString(",")
                AppLog.i("RUTUBE", "Video format dimensions · ${dimensions.ifEmpty { "none" }}")
            }
        } catch (error: JSONException) {
            AppLog.e("RUTUBE", "Could not parse yt-dlp JSON: ${error.message}", error)
            throw ExtractionException("RUTUBE вернул непонятный ответ.", cause = error)
        }
    }

    override suspend fun download(
        url: String,
        selector: String?,
        audioOnly: Boolean,
        targetDir: File,
        onProgress: (percent: Float) -> Unit,
    ): File {
        ensureReady()
        AppLog.i(
            "RUTUBE",
            "Download start · audioOnly=$audioOnly · selector=${selector ?: "default"} · session=$hasSession · url=$url",
        )
        val request = YoutubeDLRequest(url).apply {
            addOption("--no-playlist")
            addOption("--no-mtime")
            addOption("--no-warnings")
            if (hasSession) addOption("--cookies", cookiesFile.absolutePath)
            addOption("-o", File(targetDir, "out.%(ext)s").absolutePath)
            if (audioOnly) {
                addOption("-f", RutubeProvider.AUDIO_SELECTOR)
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "192K")
                addOption("--convert-thumbnails", "jpg")
                addOption("--embed-thumbnail")
            } else {
                addOption("-f", selector ?: RutubeProvider.BEST_VIDEO_SELECTOR)
                addOption("--merge-output-format", "mp4")
            }
        }

        try {
            executeDownload(request, onProgress)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            AppLog.e("RUTUBE", "Download failed: ${error.message}", error)
            throw DownloadFailure(failureMessage(error), error)
        }

        val files = targetDir.listFiles().orEmpty()
        val expected = if (audioOnly) "out.mp3" else "out.mp4"
        val result = files.firstOrNull { it.name == expected }
            ?: files.firstOrNull {
                it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }
            ?: throw DownloadFailure("yt-dlp не создал файл RUTUBE.")

        AppLog.i("RUTUBE", "Download success · file=${result.name} · bytes=${result.length()}")
        if (!audioOnly) {
            val dimensions = runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(result.absolutePath)
                    val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                    val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                    if (width.isNullOrBlank() || height.isNullOrBlank()) null else "${width}x${height}"
                } finally {
                    retriever.release()
                }
            }.getOrNull()
            AppLog.i("RUTUBE", "Saved video dimensions · ${dimensions ?: "unavailable"}")
        }
        return result
    }

    private suspend fun ensureReady() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            AppLog.i("RUTUBE", "Initializing yt-dlp runtime")
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().init(appContext)
                    FFmpeg.getInstance().init(appContext)
                }
            } catch (error: Exception) {
                AppLog.e("RUTUBE", "Runtime initialization failed: ${error.message}", error)
                throw ExtractionException("Не удалось запустить yt-dlp для RUTUBE: ${error.message}", cause = error)
            }
            ready = true
            AppLog.i("RUTUBE", "yt-dlp runtime ready")
        }
    }

    private suspend fun executeDownload(
        request: YoutubeDLRequest,
        onProgress: (percent: Float) -> Unit,
    ) {
        val processId = UUID.randomUUID().toString()
        coroutineScope {
            val killer = launch(Dispatchers.IO) {
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        runCatching { YoutubeDL.getInstance().destroyProcessById(processId) }
                    }
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().execute(request, processId) { progress, _, _ -> onProgress(progress) }
                }
            } finally {
                killer.cancel()
            }
        }
    }

    private fun toExtractionException(error: YoutubeDLException): ExtractionException {
        val text = error.message.orEmpty()
        val kind = when {
            AUTH_MARKERS.any { text.contains(it, ignoreCase = true) } ->
                ExtractionException.Kind.AUTH_REQUIRED
            UNAVAILABLE_MARKERS.any { text.contains(it, ignoreCase = true) } ->
                ExtractionException.Kind.UNAVAILABLE
            else -> ExtractionException.Kind.FAILED
        }
        return ExtractionException(failureMessage(error), kind, error)
    }

    private fun failureMessage(error: YoutubeDLException): String {
        val text = error.message.orEmpty()
        if (text.contains("handshake operation timed out", ignoreCase = true) ||
            text.contains("timed out", ignoreCase = true)) {
            return "Истекло время соединения с RUTUBE. Проверь сеть или VPN и повтори попытку."
        }
        if (AUTH_MARKERS.any { text.contains(it, ignoreCase = true) }) {
            return "RUTUBE требует вход в аккаунт. Открой Настройки → Аккаунт RUTUBE."
        }
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("ERROR:") }
            ?.substringAfter("ERROR:")?.trim()
        return when {
            line != null -> "RUTUBE: ${line.take(220)}"
            text.contains("403", ignoreCase = true) -> "RUTUBE не дал доступ к потоку. Обнови yt-dlp и повтори."
            else -> "Не удалось получить видео из RUTUBE."
        }
    }

    private fun collectCookieHeader(
        domain: String,
        header: String?,
        destination: MutableMap<Pair<String, String>, String>,
    ) {
        header.orEmpty().split(';').forEach { raw ->
            val entry = raw.trim()
            val separator = entry.indexOf('=')
            if (separator <= 0) return@forEach
            val name = entry.substring(0, separator).trim()
            val value = entry.substring(separator + 1).trim()
            if (name.isNotEmpty() && value.isNotEmpty()) destination[domain to name] = value
        }
    }

    private companion object {
        const val MAX_COOKIE_BYTES = 2L * 1024L * 1024L
        val AUTH_COOKIE_NAMES = setOf(
            "rutube_session", "sessionid", "session_id", "auth_token", "access_token",
        )
        val AUTH_MARKERS = listOf(
            "login required",
            "authentication required",
            "authorization required",
            "please log in",
            "sign in",
            "unauthorized",
            "not authorized",
            "требуется авторизация",
            "необходима авторизация",
        )
        val UNAVAILABLE_MARKERS = listOf(
            "video has been removed",
            "video is unavailable",
            "not found",
            "geo restricted",
            "not available in your country",
        )
    }
}
