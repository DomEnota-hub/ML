package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal fun interface ShazamApi {
    suspend fun recognize(signature: ShazamSignatureGenerator.Signature): MusicRecognitionResult?
}

/**
 * Minimal client for the reverse-engineered Shazam recognition endpoint used by ShazamIO.
 * It sends only the compact acoustic signature, not the original audio file.
 */
internal class ShazamClient : ShazamApi {
    private val requestMutex = Mutex()
    private var lastRequestAtMs = 0L

    override suspend fun recognize(
        signature: ShazamSignatureGenerator.Signature,
    ): MusicRecognitionResult? = requestMutex.withLock {
        val now = System.currentTimeMillis()
        val waitMs = MIN_REQUEST_INTERVAL_MS - (now - lastRequestAtMs)
        if (waitMs > 0L) delay(waitMs)
        lastRequestAtMs = System.currentTimeMillis()
        perform(signature)
    }

    private suspend fun perform(
        signature: ShazamSignatureGenerator.Signature,
    ): MusicRecognitionResult? = withContext(Dispatchers.IO) {
        val firstUuid = UUID.randomUUID().toString().uppercase()
        val secondUuid = UUID.randomUUID().toString().uppercase()
        val endpoint = String.format(RECOGNITION_URL, firstUuid, secondUuid)
        val timestamp = System.currentTimeMillis()

        val payload = JSONObject()
            .put("timezone", "Europe/Paris")
            .put(
                "signature",
                JSONObject()
                    .put("uri", signature.uri)
                    .put("samplems", signature.sampleMs),
            )
            .put("timestamp", timestamp)
            .put("context", JSONObject())
            .put("geolocation", JSONObject())

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 12_000
            connection.readTimeout = 20_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Accept-Language", "en-US")
            connection.setRequestProperty("X-Shazam-Platform", "IPHONE")
            connection.setRequestProperty("X-Shazam-AppVersion", "14.1.0")
            connection.setRequestProperty("User-Agent", USER_AGENT)

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                writer.write(payload.toString())
            }

            val status = connection.responseCode
            if (status == 404) return@withContext null
            if (status == 429) throw IOException("Shazam временно ограничил частоту запросов.")
            if (status !in 200..299) throw IOException("Shazam ответил HTTP $status.")

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            parseResponse(body)
        } finally {
            connection.disconnect()
        }
    }

    internal fun parseResponse(body: String): MusicRecognitionResult? {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val matches = root.optJSONArray("matches")
        if (matches == null || matches.length() == 0) return null

        val track = root.optJSONObject("track") ?: return null
        val title = track.optString("title").trim()
        val artist = track.optString("subtitle").trim()
        if (title.isBlank() || artist.isBlank()) return null

        val trackId = track.optString("key").trim().ifBlank {
            matches.optJSONObject(0)?.optString("id")?.trim().orEmpty()
        }.ifBlank { null }

        val images = track.optJSONObject("images")
        val artwork = images?.optString("coverarthq")?.takeIf { it.isNotBlank() }
            ?: images?.optString("coverart")?.takeIf { it.isNotBlank() }

        return MusicRecognitionResult(
            trackId = trackId,
            title = title,
            artist = artist,
            album = albumFrom(track),
            artworkUrl = artwork,
            source = RecognitionSource.SHAZAM_DIRECT,
        )
    }

    private fun albumFrom(track: JSONObject): String? {
        val sections = track.optJSONArray("sections") ?: return null
        for (sectionIndex in 0 until sections.length()) {
            val section = sections.optJSONObject(sectionIndex) ?: continue
            if (!section.optString("type").equals("SONG", ignoreCase = true)) continue
            val metadata = section.optJSONArray("metadata") ?: continue
            for (index in 0 until metadata.length()) {
                val item = metadata.optJSONObject(index) ?: continue
                if (item.optString("title").equals("Album", ignoreCase = true)) {
                    return item.optString("text").trim().takeIf { it.isNotBlank() }
                }
            }
        }
        return null
    }

    private companion object {
        const val MIN_REQUEST_INTERVAL_MS = 1_000L
        const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)"
        const val RECOGNITION_URL =
            "https://amp.shazam.com/discovery/v5/en-US/GB/iphone/-/tag/%s/%s" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3" +
                "&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
    }
}
