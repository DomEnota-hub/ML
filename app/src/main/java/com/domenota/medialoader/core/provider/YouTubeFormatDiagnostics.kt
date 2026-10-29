package com.domenota.medialoader.core.provider

import org.json.JSONObject

/** A bounded, allowlisted summary of yt-dlp's video formats for support logs. */
object YouTubeFormatDiagnostics {
    private val clients = setOf("WEB", "WEBS", "WEBE", "MWEB", "ANDR", "ANDRV", "TV", "TVD")

    fun summary(json: String): String {
        val formats = JSONObject(json).optJSONArray("formats") ?: return "none"
        val entries = (0 until formats.length()).mapNotNull { index ->
            val format = formats.optJSONObject(index) ?: return@mapNotNull null
            if (format.optString("vcodec").let { it.isBlank() || it == "none" || it == "null" }) return@mapNotNull null
            val height = format.optInt("height").takeIf { it in 1..4320 } ?: return@mapNotNull null
            val protocol = when (format.optString("protocol")) {
                "https" -> "https"
                "http" -> "http"
                "m3u8_native", "m3u8" -> "hls"
                "http_dash_segments", "dash" -> "dash"
                else -> "other"
            }
            // yt-dlp includes the short player client in format_note with --verbose.
            val client = format.optString("format_note").substringAfterLast(',').trim()
                .takeIf { it in clients } ?: "unknown"
            "${height}p:$protocol:$client"
        }.distinct().take(24)
        return entries.joinToString(",").ifEmpty { "none" }
    }
}
