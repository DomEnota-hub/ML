package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import java.io.EOFException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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
    // Shared across all instances: live recognition, saved videos and Instagram clips
    // must not independently flood the same upstream endpoint.
    override suspend fun recognize(
        signature: ShazamSignatureGenerator.Signature,
    ): MusicRecognitionResult? = requestThrottle.execute {
        try {
            perform(signature)
        } catch (error: IOException) {
            // Retry a dropped connection / timeout just once; never retry 429,
            // other HTTP errors, or a valid "no match" response.
            if (!isTransientShazamNetworkError(error)) throw error
            delay(1_200L)
            perform(signature)
        }
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
            if (status == 429) {
                val retryAfter = parseShazamRetryAfterMs(
                    connection.getHeaderField("Retry-After"), System.currentTimeMillis())
                throw ShazamRateLimitException(retryAfter ?: DEFAULT_RATE_LIMIT_MS)
            }
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
        private val requestThrottle = ShazamRequestThrottle()
        const val USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 6.0.1; SM-G920F Build/MMB29K)"
        const val RECOGNITION_URL =
            "https://amp.shazam.com/discovery/v5/en-US/GB/iphone/-/tag/%s/%s" +
                "?sync=true&webv3=true&sampling=true&connected=&shazamapiversion=v3" +
                "&sharehub=true&hubv5minorversion=v5.1&hidelb=true&video=v3"
    }
}


/** A 429 is a cooldown, not a missing song. Never retry the blocked request immediately. */
internal class ShazamRateLimitException(val retryInMs: Long) : IOException(
    "Shazam ограничил запросы. Повторите примерно через " +
        if (retryInMs < 60_000L) ((retryInMs + 999L) / 1_000L).toString() + " с."
        else ((retryInMs + 59_999L) / 60_000L).toString() + " мин."
)

/**
 * Parse Retry-After in seconds or HTTP-date form. Invalid values use our
 * conservative cooldown. Cap upstream delays to avoid arithmetic overflow.
 */
internal fun parseShazamRetryAfterMs(value: String?, nowEpochMs: Long): Long? {
    val raw = value?.trim().orEmpty().takeIf { it.isNotEmpty() } ?: return null
    val duration = raw.toLongOrNull()?.takeIf { it >= 0L }?.let { seconds ->
        if (seconds > MAX_SHZ_COOLDOWN_MS / 1_000L) MAX_SHZ_COOLDOWN_MS else seconds * 1_000L
    } ?: runCatching {
        ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME)
            .toInstant().toEpochMilli() - nowEpochMs
    }.getOrNull() ?: return null
    return duration.coerceIn(0L, MAX_SHZ_COOLDOWN_MS)
}

internal fun isTransientShazamNetworkError(error: IOException): Boolean =
    error is SocketTimeoutException || error is EOFException || error is SocketException ||
        (error.message?.contains("unexpected end of stream", ignoreCase = true) == true)

/**
 * One in-process gate for all ShazamClient instances. The lock serializes
 * network attempts. After a 429 subsequent calls fail fast until cooldown ends.
 * Monotonic time avoids device-clock changes invalidating the wait.
 */
internal class ShazamRequestThrottle(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val minSpacingMs: Long = 1_500L,
    private val minCooldownMs: Long = DEFAULT_RATE_LIMIT_MS,
) {
    private val mutex = Mutex()
    private var lastStartedAtMs: Long? = null
    private var cooldownUntilMs: Long? = null

    suspend fun <T> execute(block: suspend () -> T): T = mutex.withLock {
        val remaining = (cooldownUntilMs?.minus(nowMs()) ?: 0L).coerceAtLeast(0L)
        if (remaining > 0L) throw ShazamRateLimitException(remaining)
        val last = lastStartedAtMs
        if (last != null) {
            val untilNext = (minSpacingMs - (nowMs() - last)).coerceAtLeast(0L)
            if (untilNext > 0L) delay(untilNext)
        }
        lastStartedAtMs = nowMs()
        try {
            block()
        } catch (rateLimited: ShazamRateLimitException) {
            val pause = maxOf(minCooldownMs, rateLimited.retryInMs)
            cooldownUntilMs = nowMs() + pause
            throw ShazamRateLimitException(pause)
        }
    }
}

private const val DEFAULT_RATE_LIMIT_MS = 60_000L
private const val MAX_SHZ_COOLDOWN_MS = 86_400_000L
