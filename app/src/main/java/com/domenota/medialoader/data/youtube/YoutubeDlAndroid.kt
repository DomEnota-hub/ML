package com.domenota.medialoader.data.youtube

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.provider.ExtractionException
import com.domenota.medialoader.core.provider.StreamExtractor
import com.domenota.medialoader.core.provider.StreamInfo
import com.domenota.medialoader.core.provider.YouTubeFormats
import com.domenota.medialoader.core.provider.YouTubeFormatDiagnostics
import com.domenota.medialoader.core.provider.YouTubeExtractionChoice
import com.domenota.medialoader.core.provider.YouTubeWarningDiagnostics
import com.domenota.medialoader.core.provider.YtDlpJson
import com.domenota.medialoader.data.download.DownloadFailure
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException

/**
 * The only class that touches youtubedl-android (yt-dlp + ffmpeg bundled for Android).
 * Everything else sees [StreamExtractor] and [YtDlpDownloader].
 */
class YoutubeDlAndroid(context: Context) : StreamExtractor, YtDlpDownloader {
    private val appContext = context.applicationContext
    private val initLock = Mutex()
    private val updateLock = Mutex()
    private val state = appContext.getSharedPreferences("youtube_runtime", Context.MODE_PRIVATE)
    @Volatile private var ready = false
    private data class CachedVideo(
        val info: StreamInfo,
        val anonymous: Boolean,
        val proofBased: Boolean,
        val savedAt: Long,
    )
    private val videoCache = LinkedHashMap<String, CachedVideo>()
    private val cookiesFile get() = File(appContext.filesDir, "youtube-cookies.txt")
    val hasCookies get() = cookiesFile.exists() && cookiesFile.length() > 0L
    val cookieSource: String? get() = state.getString(KEY_COOKIE_SOURCE, null)

    val lastUpdateStatus: String?
        get() = state.getLong(KEY_LAST_UPDATE, 0L).takeIf { it > 0L }?.let { timestamp ->
            "Обновлён: ${SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))}"
        }

    init {
        AppLog.init(appContext)
    }

    fun importCookies(uri: Uri) {
        val temporary = File(appContext.filesDir, "youtube-cookies.tmp")
        try {
            val input = appContext.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("Не удалось открыть файл")
            input.use { source ->
                temporary.outputStream().use { target ->
                    val copied = source.copyTo(target)
                    require(copied in 1..MAX_COOKIE_BYTES) { "Файл cookies пустой или слишком большой" }
                }
            }
            val header = temporary.bufferedReader().use { it.readLine().orEmpty() }
            require(
                header.startsWith("# Netscape HTTP Cookie File") ||
                    header.startsWith("# HTTP Cookie File"),
            ) { "Нужен файл cookies в формате Netscape" }
            replaceCookiesFile(temporary)
            state.edit().putString(KEY_COOKIE_SOURCE, COOKIE_SOURCE_FILE).apply()
            AppLog.i("YouTube", "Cookies imported · bytes=${cookiesFile.length()}")
        } catch (error: Exception) {
            AppLog.e("YouTube", "Cookies import failed: ${error.message}", error)
            throw error
        } finally {
            temporary.delete()
        }
    }

    /** Saves authenticated WebView cookies as a private Netscape file consumable by yt-dlp. */
    fun saveWebSession(
        youtubeCookies: String?,
        googleCookies: String?,
        accountsCookies: String?,
    ): Boolean {
        val values = linkedMapOf<Pair<String, String>, String>()
        collectCookieHeader(".youtube.com", youtubeCookies, values)
        collectCookieHeader(".google.com", googleCookies, values)
        collectCookieHeader(".google.com", accountsCookies, values)
        val authenticated = values.keys.any { (_, name) ->
            AUTH_COOKIE_NAMES.any { it.equals(name, ignoreCase = true) }
        }
        if (!authenticated) return false

        val temporary = File(appContext.filesDir, "youtube-web-cookies.tmp")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.appendLine("# Netscape HTTP Cookie File")
                writer.appendLine("# Generated locally by MediaLoader from the user's YouTube WebView session.")
                values.forEach { (key, value) ->
                    val (domain, name) = key
                    writer.append(domain).append('\t')
                        .append("TRUE\t/\tTRUE\t0\t")
                        .append(name).append('\t').append(value).appendLine()
                }
            }
            require(temporary.length() in 1..MAX_COOKIE_BYTES) { "Сессия YouTube слишком большая" }
            replaceCookiesFile(temporary)
            state.edit().putString(KEY_COOKIE_SOURCE, COOKIE_SOURCE_WEB).apply()
            AppLog.i("YouTube", "Web session saved · cookies=${values.size}")
            return true
        } catch (error: Exception) {
            AppLog.e("YouTube", "Web session save failed: ${error.message}", error)
            return false
        } finally {
            temporary.delete()
        }
    }

    fun clearCookies() {
        cookiesFile.delete()
        state.edit().remove(KEY_COOKIE_SOURCE).apply()
        YouTubeLocalProof.clear()
        AppLog.i("YouTube", "Cookies removed")
    }

    /** Diagnostic only: verify whether a browser-bound token unlocks real yt-dlp formats. */
    suspend fun probeLocalToken(url: String, proof: YouTubeLocalProof.Value) {
        try {
            ensureReady()
            val args = "youtube:player_client=mweb;player_skip=webpage,configs;" +
                "visitor_data=${proof.visitorData};po_token=mweb.gvs+${proof.token}"
            val info = YtDlpJson.parse(executeExtract(url, args, includeCookies = false).trim())
            logFormats("local-token-probe", info)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // yt-dlp stderr can contain request arguments. Never log the raw error here.
            AppLog.w("YouTube", "Local token probe failed")
        }
    }

    private fun replaceCookiesFile(temporary: File) {
        if (cookiesFile.exists()) require(cookiesFile.delete()) { "Не удалось заменить cookies" }
        require(temporary.renameTo(cookiesFile)) { "Не удалось сохранить cookies" }
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

    /** First use unpacks the bundled Python and ffmpeg, which takes a few seconds. */
    private suspend fun ensureReady() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            AppLog.i("YouTube", "Initializing yt-dlp runtime")
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
            }
            ready = true
            val version = YoutubeDL.getInstance().version(appContext)
                ?.takeIf { it.matches(Regex("[0-9]{4}\\.[0-9]{2}\\.[0-9]{2}(?:\\.[0-9]+)?")) }
                ?: "unknown"
            val quickJs = File(appContext.applicationInfo.nativeLibraryDir, "libqjs.so")
            AppLog.i("YouTube", "yt-dlp runtime ready · version=$version · quickJsPresent=${quickJs.exists()}")
        }
    }

    /** Updates the extractor in app-private storage. Invoke when no downloads are active. */
    suspend fun update(): String {
        ensureReady()
        return updateLock.withLock {
            AppLog.i("YouTube", "yt-dlp update requested")
            try {
                withContext(Dispatchers.IO) {
                    YoutubeDL.getInstance().updateYoutubeDL(appContext, YoutubeDL.UpdateChannel.STABLE)
                }
                val now = System.currentTimeMillis()
                state.edit().putLong(KEY_LAST_UPDATE, now).apply()
                val text = lastUpdateStatus ?: "yt-dlp обновлён или уже актуален"
                AppLog.i("YouTube", text)
                text
            } catch (error: Exception) {
                AppLog.e("YouTube", "yt-dlp update failed: ${error.message}", error)
                throw error
            }
        }
    }

    override suspend fun extract(url: String): StreamInfo {
        try {
            ensureReady()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.e("YouTube", "Runtime initialization failed: ${error.message}", error)
            throw ExtractionException("Не удалось запустить yt-dlp: ${error.message}", cause = error)
        }
        AppLog.i("YouTube", "Analyze start · cookies=$hasCookies · source=${cookieSource ?: "none"}")
        synchronized(videoCache) {
            videoCache[url]?.takeIf { System.currentTimeMillis() - it.savedAt < 5 * 60_000L }
        }?.let { cached ->
            AppLog.i("YouTube", "Analyze cache hit · formats=${cached.info.formats.size}")
            return cached.info
        }

        // Both independent requests previously took 12-16s and 36-40s in
        // sequence. The Android wrapper supports distinct concurrent process IDs.
        return supervisorScope {
            val publicTask = async {
                try {
                    YtDlpJson.parse(executeExtract(url, ANONYMOUS_EXTRACTOR_ARGS, includeCookies = false).trim())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    AppLog.w("YouTube", "Public client did not provide formats", error)
                    null
                }
            }
            val savedTask = async {
                try {
                    Result.success(extractWithSavedSession(url))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Result.failure(error)
                }
            }
            val proofTask = async {
                val proof = withTimeoutOrNull(16_000L) {
                    while (true) {
                        YouTubeLocalProof.available()?.let { return@withTimeoutOrNull it }
                        delay(400)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    null
                } ?: return@async null
                try {
                    YtDlpJson.parse(executeExtract(url, YouTubeProofArgs.forWeb(proof)).trim())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // yt-dlp errors may contain extractor args, including the proof.
                    AppLog.w("YouTube", "Browser Web proof did not provide formats")
                    null
                }
            }
            data class Candidate(val info: StreamInfo, val source: String)
            val publicInfo = publicTask.await()
            publicInfo?.let { logFormats("public", it) }
            if (publicInfo != null && YouTubeExtractionChoice.publicResultIsEnough(publicInfo)) {
                savedTask.cancel()
                proofTask.cancel()
                rememberVideo(url, publicInfo, anonymous = true)
                AppLog.i("YouTube", "Analyze success · client=public · formats=${publicInfo.formats.size}")
                return@supervisorScope publicInfo
            }
            if (publicInfo != null && YouTubeExtractionChoice.publicResultCanBoundWait(publicInfo)) {
                // 720p with audio is usable. Give authenticated/proof clients a short chance
                // to improve quality, then stop their slow probes instead of blocking the picker.
                val waitStarted = SystemClock.elapsedRealtime()
                val quickSaved = async {
                    withTimeoutOrNull(4_000L) { savedTask.await().getOrNull() }
                }
                val quickProof = async {
                    withTimeoutOrNull(4_000L) { proofTask.await() }
                }
                val savedInfo = quickSaved.await()
                val proofInfo = quickProof.await()
                savedTask.cancel()
                proofTask.cancel()
                savedInfo?.let { logFormats("saved-session", it) }
                proofInfo?.let { logFormats(YouTubeProofArgs.safeLabel, it) }
                val candidates = listOfNotNull(
                    publicInfo.let { Candidate(it, "public") },
                    savedInfo?.let { Candidate(it, "saved-session") },
                    proofInfo?.let { Candidate(it, YouTubeProofArgs.safeLabel) },
                )
                val chosen = candidates.reduce { best, next ->
                    if (YouTubeExtractionChoice.preferPublic(next.info, best.info)) next else best
                }
                rememberVideo(url, chosen.info,
                    anonymous = chosen.source == "public",
                    proofBased = chosen.source == YouTubeProofArgs.safeLabel)
                AppLog.i("YouTube", "Analyze bounded enhancement · extraMs=${SystemClock.elapsedRealtime() - waitStarted} · client=${chosen.source}")
                return@supervisorScope chosen.info
            }
            val savedResult = savedTask.await()
            val proofInfo = proofTask.await()
            val savedInfo = savedResult.getOrNull()
            savedInfo?.let { logFormats("saved-session", it) }
            proofInfo?.let { logFormats(YouTubeProofArgs.safeLabel, it) }
            if (savedInfo == null && publicInfo == null && proofInfo == null) {
                throw savedResult.exceptionOrNull() ?: ExtractionException("YouTube не вернул форматы видео.")
            }
            if (savedInfo == null) AppLog.w("YouTube", "Saved-session client did not provide formats")
            val candidates = listOfNotNull(
                savedInfo?.let { Candidate(it, "saved-session") },
                publicInfo?.let { Candidate(it, "public") },
                proofInfo?.let { Candidate(it, YouTubeProofArgs.safeLabel) },
            )
            val chosen = candidates.reduce { best, next ->
                if (YouTubeExtractionChoice.preferPublic(next.info, best.info)) next else best
            }
            rememberVideo(
                url, chosen.info,
                anonymous = chosen.source == "public",
                proofBased = chosen.source == YouTubeProofArgs.safeLabel,
            )
            AppLog.i("YouTube", "Analyze success · client=${chosen.source} · formats=${chosen.info.formats.size}")
            chosen.info
        }
    }

    private fun logFormats(client: String, info: StreamInfo) {
        val videoHeights = info.formats.filter { it.hasVideo && it.height != null }
            .map { "${it.height}p${if (it.hasAudio) "+audio" else ""}" }
            .distinct().take(20).joinToString(",")
        AppLog.i("YouTube", "Analyze result · client=$client · formats=${info.formats.size} · " +
            "maxHeight=${YouTubeExtractionChoice.maxVideoHeight(info)} · audio=${info.formats.any { it.hasAudio }} · " +
            "videoHeights=$videoHeights")
    }

    private fun rememberVideo(url: String, info: StreamInfo, anonymous: Boolean, proofBased: Boolean = false) {
        synchronized(videoCache) {
            if (videoCache.size >= 24) videoCache.remove(videoCache.keys.first())
            videoCache[url] = CachedVideo(info, anonymous, proofBased, System.currentTimeMillis())
        }
    }

    private suspend fun extractWithSavedSession(url: String): StreamInfo {
        val output = try {
            executeExtract(url, PRIMARY_EXTRACTOR_ARGS)
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            if (isPageReloadRequired(error)) {
                AppLog.w("YouTube", "Player asks for page reload; trying alternate client without tv_downgraded")
                try {
                    executeExtract(url, RELOAD_RECOVERY_EXTRACTOR_ARGS)
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.w("YouTube", "Alternate web player was also rejected", retryError)
                    // Public videos may be rejected only when logged-in cookies are supplied.
                    // Try a clean anonymous request; never remove the user's saved session.
                    if (hasCookies && isReloadOrUnavailable(retryError)) {
                        try {
                            AppLog.i("YouTube", "Trying anonymous public-video extraction without cookies")
                            executeExtract(url, ANONYMOUS_EXTRACTOR_ARGS, includeCookies = false)
                        } catch (anonymousError: YoutubeDLException) {
                            currentCoroutineContext().ensureActive()
                            AppLog.w("YouTube", "Anonymous extraction also failed", anonymousError)
                            throw toExtractionException(anonymousError)
                        }
                    } else if (isHttp403(retryError)) executeExtractFallback(url, retryError)
                    else throw toExtractionException(retryError)
                }
            } else if (!isHttp403(error)) {
                AppLog.e("YouTube", "Analyze failed: ${error.message}", error)
                throw toExtractionException(error)
            } else {
            AppLog.w("YouTube", "Analyze got HTTP 403", error)
            val refreshed = maybeAutoUpdateAfter403()
            if (refreshed) {
                try {
                    executeExtract(url, PRIMARY_EXTRACTOR_ARGS)
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    if (!isHttp403(retryError)) throw toExtractionException(retryError)
                    executeExtractFallback(url, retryError)
                }
            } else {
                executeExtractFallback(url, error)
            }
            }
        }
        return try {
            val initial = YtDlpJson.parse(output.trim())
            val initialHeight = initial.formats.mapNotNull { it.height }.maxOrNull() ?: 0
            val initialAudio = initial.formats.any { it.hasAudio }
            AppLog.i("YouTube", "Analyze first result · formats=${initial.formats.size} · maxHeight=$initialHeight · audio=$initialAudio")
            // The old low-resolution probe doubled analysis time even when a
            // playable video with audio was already available. Recover only
            // when the first client did not expose an audio track.
            val info = if (initialHeight in 1..360 && !initialAudio) {
                val alternate = runCatching {
                    YtDlpJson.parse(executeExtract(url, ALTERNATE_EXTRACTOR_ARGS).trim())
                }.getOrNull()
                currentCoroutineContext().ensureActive()
                alternate?.takeIf { candidate ->
                    candidate.formats.any { it.hasAudio } ||
                        (candidate.formats.mapNotNull { it.height }.maxOrNull() ?: 0) > initialHeight
                } ?: initial
            } else initial
            info
        } catch (error: JSONException) {
            AppLog.e("YouTube", "Could not parse yt-dlp JSON: ${error.message}", error)
            throw ExtractionException("yt-dlp вернул непонятный ответ.", cause = error)
        }
    }

    private suspend fun executeExtractFallback(url: String, original: YoutubeDLException): String {
        AppLog.w("YouTube", "Analyze retrying with web_embedded after 403")
        return try {
            executeExtract(url, EMBEDDED_EXTRACTOR_ARGS)
        } catch (fallbackError: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            AppLog.e("YouTube", "Analyze fallback failed: ${fallbackError.message}", fallbackError)
            if (isAuthRequired(fallbackError)) throw toExtractionException(fallbackError)
            throw ExtractionException(RECOVERY_FAILED_MESSAGE, cause = original)
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
            "YouTube",
            "Download start · audioOnly=$audioOnly · cookies=$hasCookies · source=${cookieSource ?: "none"}",
        )
        val cached = synchronized(videoCache) { videoCache[url] }
        if (cached?.proofBased == true) {
            val proof = YouTubeLocalProof.available()
            if (proof != null) {
                try {
                    AppLog.i("YouTube", "Download client · ${YouTubeProofArgs.safeLabel}")
                    executeDownload(
                        buildDownloadRequest(url, selector, audioOnly, targetDir, YouTubeProofArgs.forWeb(proof)),
                        onProgress,
                    )
                    return finishDownload(targetDir, audioOnly)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Do not log errors carrying a token in their command line.
                    AppLog.w("YouTube", "Browser Web proof download failed; trying saved session")
                    removePartialOutput(targetDir)
                }
            } else {
                AppLog.w("YouTube", "Browser Web proof expired; trying saved session")
            }
        }
        val publicFirst = cached?.anonymous == true
        val firstClient = if (publicFirst) ANONYMOUS_EXTRACTOR_ARGS else PRIMARY_EXTRACTOR_ARGS
        AppLog.i("YouTube", "Download client · ${if (publicFirst) "public" else "saved-session"}")

        try {
            executeDownload(
                buildDownloadRequest(url, selector, audioOnly, targetDir, firstClient,
                    includeCookies = !publicFirst),
                onProgress,
            )
        } catch (error: YoutubeDLException) {
            currentCoroutineContext().ensureActive()
            var failed = error
            if (isPageReloadRequired(error)) {
                AppLog.w("YouTube", "Download player requested reload; trying alternate web_safari client")
                removePartialOutput(targetDir)
                try {
                    executeDownload(
                        buildDownloadRequest(url, selector, audioOnly, targetDir, RELOAD_RECOVERY_EXTRACTOR_ARGS),
                        onProgress,
                    )
                    return finishDownload(targetDir, audioOnly)
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.e("YouTube", "Reload recovery download failed: ${retryError.message}", retryError)
                    if (hasCookies && isReloadOrUnavailable(retryError)) {
                        try {
                            AppLog.i("YouTube", "Trying anonymous public-video download without cookies")
                            removePartialOutput(targetDir)
                            executeDownload(
                                buildDownloadRequest(
                                    url, selector, audioOnly, targetDir, ANONYMOUS_EXTRACTOR_ARGS,
                                    includeCookies = false,
                                ),
                                onProgress,
                            )
                            return finishDownload(targetDir, audioOnly)
                        } catch (anonymousError: YoutubeDLException) {
                            currentCoroutineContext().ensureActive()
                            AppLog.w("YouTube", "Anonymous download also failed", anonymousError)
                            throw DownloadFailure(failureMessage(anonymousError), anonymousError)
                        }
                    }
                    if (!isHttp403(retryError)) throw DownloadFailure(failureMessage(retryError), retryError)
                    // Let the existing HTTP 403 recovery handle the second failure.
                    failed = retryError
                    removePartialOutput(targetDir)
                }
            }
            if (!audioOnly && selector != null && isFormatUnavailable(failed)) {
                removePartialOutput(targetDir)
                try {
                    executeDownload(
                        buildDownloadRequest(url, selector, false, targetDir, ALTERNATE_EXTRACTOR_ARGS),
                        onProgress,
                    )
                    return finishDownload(targetDir, false)
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.w("YouTube", "Alternate player did not provide selected format", retryError)
                }
                // Analysis can succeed only through the anonymous client when
                // the saved web session is rejected. Retry the selected quality
                // with that same client before reporting the format unavailable.
                if (hasCookies) {
                    removePartialOutput(targetDir)
                    try {
                        executeDownload(
                            buildDownloadRequest(
                                url, selector, false, targetDir,
                                if (publicFirst) PRIMARY_EXTRACTOR_ARGS else ANONYMOUS_EXTRACTOR_ARGS,
                                includeCookies = publicFirst,
                            ),
                            onProgress,
                        )
                        return finishDownload(targetDir, false)
                    } catch (anonymousError: YoutubeDLException) {
                        currentCoroutineContext().ensureActive()
                        AppLog.w("YouTube", "Other client did not provide selected format", anonymousError)
                    }
                }
            }
            if (audioOnly && publicFirst && isFormatUnavailable(failed) && hasCookies) {
                removePartialOutput(targetDir)
                try {
                    executeDownload(
                        buildDownloadRequest(url, selector, true, targetDir, PRIMARY_EXTRACTOR_ARGS),
                        onProgress,
                    )
                    return finishDownload(targetDir, true)
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    AppLog.w("YouTube", "Saved-session audio fallback failed", retryError)
                }
            }
            if (!isHttp403(failed)) {
                AppLog.e("YouTube", "Download failed: ${failed.message}", failed)
                throw DownloadFailure(failureMessage(failed), failed)
            }

            AppLog.w("YouTube", "Download got HTTP 403", failed)
            var last403 = failed
            if (maybeAutoUpdateAfter403()) {
                removePartialOutput(targetDir)
                AppLog.i("YouTube", "Retrying download after automatic yt-dlp update")
                try {
                    executeDownload(
                        buildDownloadRequest(url, selector, audioOnly, targetDir, PRIMARY_EXTRACTOR_ARGS),
                        onProgress,
                    )
                    last403 = error
                } catch (retryError: YoutubeDLException) {
                    currentCoroutineContext().ensureActive()
                    if (!isHttp403(retryError)) {
                        AppLog.e("YouTube", "Download after update failed: ${retryError.message}", retryError)
                        throw DownloadFailure(failureMessage(retryError), retryError)
                    }
                    last403 = retryError
                }
                if (targetDir.listFiles().orEmpty().any { it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }) {
                    return finishDownload(targetDir, audioOnly)
                }
            }

            removePartialOutput(targetDir)
            AppLog.w("YouTube", "Retrying download with web_embedded only")
            try {
                executeDownload(
                    buildDownloadRequest(url, selector, audioOnly, targetDir, EMBEDDED_EXTRACTOR_ARGS),
                    onProgress,
                )
            } catch (fallbackError: YoutubeDLException) {
                currentCoroutineContext().ensureActive()
                AppLog.e("YouTube", "Download fallback failed: ${fallbackError.message}", fallbackError)
                if (isAuthRequired(fallbackError)) {
                    throw DownloadFailure(failureMessage(fallbackError), fallbackError)
                }
                throw DownloadFailure(RECOVERY_FAILED_MESSAGE, last403)
            }
        }

        return finishDownload(targetDir, audioOnly)
    }

    private fun finishDownload(targetDir: File, audioOnly: Boolean): File {
        val files = targetDir.listFiles().orEmpty()
        val expected = if (audioOnly) "out.mp3" else "out.mp4"
        val result = files.firstOrNull { it.name == expected }
            ?: files.firstOrNull {
                it.name.startsWith("out.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }
            ?: throw DownloadFailure("yt-dlp не создал файл.")
        AppLog.i("YouTube", "Download success · file=${result.name} · bytes=${result.length()}")
        return result
    }

    private suspend fun maybeAutoUpdateAfter403(): Boolean {
        val now = System.currentTimeMillis()
        val lastUpdate = state.getLong(KEY_LAST_UPDATE, 0L)
        val lastAttempt = state.getLong(KEY_LAST_AUTO_ATTEMPT, 0L)
        if (lastUpdate > 0L && now - lastUpdate < AUTO_UPDATE_FRESH_MS) {
            AppLog.i("YouTube", "Automatic yt-dlp refresh skipped: current extractor was updated recently")
            return false
        }
        if (lastAttempt > 0L && now - lastAttempt < AUTO_UPDATE_COOLDOWN_MS) {
            AppLog.i("YouTube", "Automatic yt-dlp refresh skipped: cooldown active")
            return false
        }
        state.edit().putLong(KEY_LAST_AUTO_ATTEMPT, now).apply()
        AppLog.i("YouTube", "HTTP 403 recovery: updating yt-dlp automatically")
        return try {
            update()
            true
        } catch (error: Exception) {
            AppLog.w("YouTube", "Automatic yt-dlp update did not succeed", error)
            false
        }
    }

    private suspend fun executeExtract(
        url: String,
        extractorArgs: String,
        includeCookies: Boolean = true,
        compactOutput: Boolean = true,
    ): String {
        val request = YoutubeDLRequest(url).apply {
            if (compactOutput) {
                addOption("--simulate")
                addOption("--print", YouTubeCompactOutput.METADATA)
                addOption("--print", YouTubeCompactOutput.FORMATS)
            } else {
                addOption("-J")
            }
            // yt-dlp puts a short player-client name in format_note with verbose enabled.
            // Only the allowlisted summary below is written to the app log.
            addOption("--verbose")
            addOption("--no-playlist")
            addOption("--socket-timeout", "15")
            addOption("--retries", "1")
            addOption("--extractor-retries", "1")
            addOption("--extractor-args", extractorArgs)
            // The Android wrapper otherwise forces --no-cache-dir. Reuse yt-dlp's
            // player cache between analyses; Android may evict it when space is low.
            addOption("--cache-dir", File(appContext.cacheDir, "yt-dlp").absolutePath)
            if (includeCookies && hasCookies) addOption("--cookies", cookiesFile.absolutePath)
        }
        val processId = UUID.randomUUID().toString()
        val started = SystemClock.elapsedRealtime()
        AppLog.i("YouTube", "Analyze attempt · cookies=${includeCookies && hasCookies} · compact=$compactOutput")
        val rawOutput = try {
            withTimeout(60_000L) {
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
                            YoutubeDL.getInstance().execute(request, processId).also { response ->
                                AppLog.i("YouTube", "Analyze process · wrapperMs=${response.elapsedTime} · stdoutChars=${response.out.length}")
                                val reasons = YouTubeWarningDiagnostics.classify(response.err)
                                if (reasons.isNotEmpty()) {
                                    AppLog.i("YouTube", "Analyze diagnostics · reasons=${reasons.joinToString(",")}")
                                }
                            }.out
                        }
                    } finally {
                        killer.cancel()
                    }
                }
            }.also { AppLog.i("YouTube", "Analyze attempt completed · ms=${SystemClock.elapsedRealtime() - started}") }
        } catch (error: TimeoutCancellationException) {
            AppLog.w("YouTube", "Analyze attempt timed out · ms=${SystemClock.elapsedRealtime() - started}")
            throw ExtractionException("YouTube не ответил за 60 секунд. Проверьте сеть или VPN и повторите анализ.", cause = error)
        }
        val output = if (compactOutput) {
            try {
                YouTubeCompactOutput.toJson(rawOutput)
            } catch (error: Exception) {
                AppLog.w("YouTube", "Compact format response invalid; retrying full JSON · type=${error.javaClass.simpleName}")
                return executeExtract(url, extractorArgs, includeCookies, compactOutput = false)
            }
        } else rawOutput
        runCatching { YouTubeFormatDiagnostics.summary(output) }
            .onSuccess {
                AppLog.i("YouTube", "Analyze format sources · cookies=${includeCookies && hasCookies} · $it")
            }
        return output
    }

    private fun buildDownloadRequest(
        url: String,
        selector: String?,
        audioOnly: Boolean,
        targetDir: File,
        extractorArgs: String,
        includeCookies: Boolean = true,
    ) = YoutubeDLRequest(url).apply {
        addOption("--no-playlist")
        addOption("--no-mtime")
        addOption("--no-warnings")
        addOption("--extractor-args", extractorArgs)
        if (includeCookies && hasCookies) addOption("--cookies", cookiesFile.absolutePath)
        addOption("-o", File(targetDir, "out.%(ext)s").absolutePath)
        if (audioOnly) {
            addOption("-f", YouTubeFormats.AUDIO_SELECTOR)
            addOption("-x")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "192K")
            addOption("--convert-thumbnails", "jpg")
            addOption("--embed-thumbnail")
        } else {
            addOption("-f", selector ?: YouTubeFormats.BEST_VIDEO_SELECTOR)
            addOption("--merge-output-format", "mp4")
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

    private fun removePartialOutput(targetDir: File) {
        targetDir.listFiles().orEmpty()
            .filter { it.name.startsWith("out.") }
            .forEach { runCatching { it.deleteRecursively() } }
    }

    private fun toExtractionException(error: YoutubeDLException): ExtractionException {
        val text = error.message.orEmpty()
        val kind = if (UNAVAILABLE_MARKERS.any { text.contains(it, ignoreCase = true) }) {
            ExtractionException.Kind.UNAVAILABLE
        } else {
            ExtractionException.Kind.FAILED
        }
        return ExtractionException(failureMessage(error), kind, error)
    }

    /** Return a user-facing reason without yt-dlp's raw URLs or video identifiers. */
    private fun failureMessage(error: YoutubeDLException): String {
        val text = error.message.orEmpty()
        return when {
            hasCookies && isBotChallenge(error) ->
                "YouTube отклонил запрос загрузчика проверкой безопасности, хотя вход выполнен. " +
                    "Попробуйте обновить yt-dlp и повторить анализ. Если ошибка останется — сохраните логи."
            isAuthRequired(error) ->
                "YouTube требует вход в аккаунт. Откройте Настройки → Аккаунт YouTube."
            isHttp403(error) -> RECOVERY_FAILED_MESSAGE
            isPageReloadRequired(error) ->
                "YouTube отклонил запрос проигрывателя. Попробуйте «Обновить yt-dlp» в настройках."
            isFormatUnavailable(error) -> "YouTube не предоставил выбранное качество. Повторите анализ видео."
            text.contains("No address associated with hostname", ignoreCase = true) ->
                "Не удалось найти сервер YouTube. Проверьте подключение и повторите попытку."
            else -> "Не удалось получить видео с YouTube. Подробности доступны в логах."
        }
    }

    private fun isAuthRequired(error: YoutubeDLException): Boolean {
        val text = error.message.orEmpty()
        return text.contains("age-restricted", ignoreCase = true) ||
            text.contains("login required", ignoreCase = true) ||
            (!hasCookies && isBotChallenge(error))
    }

    private fun isBotChallenge(error: YoutubeDLException): Boolean {
        val text = error.message.orEmpty()
        return text.contains("Sign in to confirm", ignoreCase = true) ||
            text.contains("confirm you're not a bot", ignoreCase = true) ||
            text.contains("confirm you’re not a bot", ignoreCase = true)
    }

    private fun isReloadOrUnavailable(error: YoutubeDLException): Boolean {
        val message = error.message.orEmpty()
        return isPageReloadRequired(error) ||
            message.contains("This video is unavailable", ignoreCase = true)
    }

    private fun isPageReloadRequired(error: YoutubeDLException): Boolean =
        error.message.orEmpty().contains("The page needs to be reloaded", ignoreCase = true)

    private fun isHttp403(error: YoutubeDLException): Boolean {
        val text = error.message.orEmpty()
        return text.contains("HTTP Error 403", ignoreCase = true) ||
            text.contains("403: Forbidden", ignoreCase = true)
    }

    private fun isFormatUnavailable(error: YoutubeDLException): Boolean =
        error.message.orEmpty().contains("Requested format is not available", ignoreCase = true)

    private companion object {
        const val MAX_COOKIE_BYTES = 2L * 1024 * 1024
        // web_safari can expose HLS streams when HTTPS adaptive formats are SABR-only.
        const val PRIMARY_EXTRACTOR_ARGS = "youtube:player_client=default,web_safari,web_embedded"
        // yt-dlp issue #17389: tv_downgraded may report UNPLAYABLE even for public videos
        // with valid cookies. Test alternate clients only after that specific failure.
        const val RELOAD_RECOVERY_EXTRACTOR_ARGS =
            "youtube:player_client=web_safari,web_embedded,-tv_downgraded"
        // Do not reuse authenticated cookies when retrying a public video. The
        // yt-dlp issue #17389 reports public extraction succeeding without cookies.
        const val ANONYMOUS_EXTRACTOR_ARGS = "youtube:player_client=default"
        const val ALTERNATE_EXTRACTOR_ARGS = "youtube:player_client=android,default"
        const val EMBEDDED_EXTRACTOR_ARGS = "youtube:player_client=web_embedded"
        const val KEY_LAST_UPDATE = "last_update_ms"
        const val KEY_LAST_AUTO_ATTEMPT = "last_auto_update_attempt_ms"
        const val KEY_COOKIE_SOURCE = "cookie_source"
        const val COOKIE_SOURCE_FILE = "file"
        const val COOKIE_SOURCE_WEB = "web"
        const val AUTO_UPDATE_FRESH_MS = 24L * 60L * 60L * 1000L
        const val AUTO_UPDATE_COOLDOWN_MS = 30L * 60L * 1000L
        const val RECOVERY_FAILED_MESSAGE =
            "YouTube не дал доступ к потоку. yt-dlp и резервный клиент не помогли — откройте Логи для диагностики."
        val AUTH_COOKIE_NAMES = setOf(
            "SAPISID", "APISID", "SID", "HSID", "SSID", "LOGIN_INFO",
            "__Secure-1PAPISID", "__Secure-3PAPISID",
        )
        val UNAVAILABLE_MARKERS = listOf(
            "Private video", "Video unavailable", "This video is not available", "members-only",
            "has been removed", "is no longer available", "blocked it",
        )
    }
}
