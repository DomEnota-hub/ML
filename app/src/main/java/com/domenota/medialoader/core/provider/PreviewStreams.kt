package com.domenota.medialoader.core.provider

import com.domenota.medialoader.core.model.MediaType
import java.net.URI

data class PreviewTrack(val url: String, val headers: Map<String, String> = emptyMap(), val mimeType: String? = null)
data class PreviewPlayback(val primary: PreviewTrack, val audio: PreviewTrack? = null, val maxVideoHeight: Int? = null)

/** Selects actual streams rather than publication pages; video and audio may be separate. */
object PreviewStreams {
    fun select(info: StreamInfo, type: MediaType, requestedHeight: Int? = null): PreviewPlayback {
        val playable = info.formats.filter { !it.hasDrm }.mapNotNull { format -> track(format)?.let { format to it } }
        if (type == MediaType.AUDIO) {
            val audio = playable.filter { it.first.hasAudio }
                .sortedWith(compareBy<Pair<StreamFormat, PreviewTrack>> { it.first.hasVideo }
                    .thenByDescending { it.first.ext in setOf("m4a", "mp4", "mp3") })
                .firstOrNull()?.second ?: throw IllegalArgumentException("Источник не предоставил аудиопоток для предпросмотра")
            return PreviewPlayback(audio)
        }
        val videos = playable.filter { it.first.hasVideo }
        val height = requestedHeight ?: 720
        val selectedHeight = videos.mapNotNull { it.first.height }.filter { it <= height }.maxOrNull()
            ?: videos.mapNotNull { it.first.height }.minOrNull()
        val candidates = videos.filter { selectedHeight == null || it.first.height == selectedHeight }
        val video = candidates.sortedWith(compareByDescending<Pair<StreamFormat, PreviewTrack>> { it.first.hasAudio }
            .thenByDescending { it.first.ext == "mp4" }).firstOrNull()
            ?: throw IllegalArgumentException("Источник не предоставил видеопоток для предпросмотра")
        val audio = if (video.first.hasAudio) null else playable.filter { it.first.hasAudio }
            .sortedBy { it.first.hasVideo }.firstOrNull()?.second
        val distinctAudio = audio?.takeUnless { it.url == video.second.url && it.headers == video.second.headers }
        return PreviewPlayback(video.second, distinctAudio, selectedHeight)
    }

    private fun track(format: StreamFormat): PreviewTrack? {
        val url = if (format.protocol == "http_dash_segments") format.manifestUrl ?: return null else format.url ?: return null
        if (!httpUrl(url)) return null
        val path = runCatching { URI(url).path.orEmpty().lowercase() }.getOrDefault("")
        val mime = when {
            format.protocol?.startsWith("m3u8") == true || path.endsWith(".m3u8") -> "application/x-mpegURL"
            format.protocol?.contains("dash") == true || path.endsWith(".mpd") -> "application/dash+xml"
            else -> null
        }
        return PreviewTrack(url, format.headers, mime)
    }

    fun httpUrl(url: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)
}

/** Only preview reuses the last result; pressing Analyze always invokes the original extractor. */
class PreviewStreamExtractor(private val delegate: StreamExtractor, private val clock: () -> Long = System::currentTimeMillis) : StreamExtractor {
    private data class Snapshot(val url: String, val info: StreamInfo, val time: Long)
    @Volatile private var last: Snapshot? = null
    override suspend fun extract(url: String): StreamInfo = delegate.extract(url).also { last = Snapshot(url, it, clock()) }
    suspend fun forPreview(url: String): StreamInfo = last?.takeIf {
        it.url == url && clock() - it.time in 0L until 5 * 60_000L
    }?.info ?: extract(url)
}
