package com.domenota.medialoader.core.provider

import org.json.JSONArray
import org.json.JSONObject

/** Parses the output of `yt-dlp -J` (a single JSON document) into [StreamInfo]. */
object YtDlpJson {
    fun parse(json: String): StreamInfo {
        val root = JSONObject(json)
        val id = root.text("id") ?: throw ExtractionException("yt-dlp не вернул идентификатор видео.")
        return StreamInfo(
            id = id,
            title = root.text("title") ?: id,
            uploader = root.text("uploader") ?: root.text("channel"),
            durationSeconds = if (root.isNull("duration")) null else root.optDouble("duration").toLong(),
            thumbnailUrl = root.text("thumbnail")
                ?: root.optJSONArray("thumbnails")?.let { thumbs ->
                    // Some extractors expose the preview only in the thumbnails list.
                    (0 until thumbs.length()).mapNotNull { thumbs.optJSONObject(it) }
                        .mapNotNull { it.text("url")?.takeIf { url -> url.startsWith("https://") } }
                        .lastOrNull()
                },
            formats = formatsOf(root.optJSONArray("formats"), headersOf(root.optJSONObject("http_headers"))),
        )
    }

    private fun formatsOf(array: JSONArray?, headers: Map<String, String>): List<StreamFormat> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull(array::optJSONObject).mapNotNull { format ->
            val formatId = format.text("format_id") ?: return@mapNotNull null
            val size = format.number("filesize") ?: format.number("filesize_approx")
            StreamFormat(
                formatId = formatId,
                height = format.number("height")?.toInt()?.takeIf { it > 0 },
                hasVideo = format.hasCodec("vcodec"),
                hasAudio = format.hasCodec("acodec"),
                ext = format.text("ext"),
                sizeBytes = size?.takeIf { it > 0 },
                fps = format.number("fps")?.toInt()?.takeIf { it > 0 },
                width = format.number("width")?.toInt()?.takeIf { it > 0 },
                url = format.text("url"),
                headers = headers + headersOf(format.optJSONObject("http_headers")),
                protocol = format.text("protocol"),
                manifestUrl = format.text("manifest_url"),
                hasDrm = format.optBoolean("has_drm", false),
            )
        }
    }

    private fun headersOf(objectValue: JSONObject?): Map<String, String> = objectValue?.keys()?.asSequence()
        ?.mapNotNull { key -> objectValue.text(key)?.takeIf { '\n' !in it && '\r' !in it }
            ?.let { key to it } }?.toMap().orEmpty()

    // JSON null and a missing key are the same here; Android's optString would return "null" for null.
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.number(key: String): Long? =
        if (isNull(key)) null else optDouble(key, Double.NaN).takeIf { !it.isNaN() }?.toLong()

    private fun JSONObject.hasCodec(key: String): Boolean = text(key).let { it != null && it != "none" }
}
