package com.domenota.medialoader.data.search

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Search-only HitMoz adapter. Never downloads music or modifies saved Instagram files. */
data class HitMozTrack(
    val artist: String,
    val title: String,
    val duration: String?,
    val pageUrl: String,
    val downloadUrl: String,
    val artworkUrl: String? = null,
)

class HitMozSearchClient {
    suspend fun search(
        artist: String,
        title: String,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 12_000,
    ): List<HitMozTrack> = withContext(Dispatchers.IO) {
        val query = listOf(artist.trim(), title.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        require(query.isNotBlank()) { "Нет названия композиции для поиска" }

        val connection = URL(searchUrl(query)).openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml")
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/125.0 Mobile Safari/537.36")
            val status = connection.responseCode
            if (status == 403 || status == 429) {
                throw IOException("Сервис ограничил доступ. Возможно, мешает VPN или защита сайта.")
            }
            if (status !in 200..299) throw IOException("Сервис ответил HTTP " + status)
            val declared = connection.contentLengthLong
            if (declared > MAX_HTML_BYTES) throw IOException("Слишком большая страница поиска")
            val html = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8 * 1024)
                var total = 0
                while (true) {
                    val count = reader.read(buffer)
                    if (count == -1) break
                    total += count
                    if (total > MAX_HTML_CHARS) throw IOException("Страница поиска слишком большая")
                    output.append(buffer, 0, count)
                }
                output.toString()
            }
            if (html.contains("Возможно Вы используете VPN", ignoreCase = true)) {
                throw IOException("Сервис просит отключить VPN. Поиск внутри приложения временно недоступен.")
            }
            val result = parseResults(html, artist, title)
            if (result.isEmpty() && !html.contains("search", ignoreCase = true)) {
                throw IOException("Не удалось прочитать результаты поиска. Возможно, сайт изменился.")
            }
            result
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val BASE_URL = "https://ru.hitmoz.org"
        private const val MAX_HTML_BYTES = 2L * 1024 * 1024
        private const val MAX_HTML_CHARS = 1_000_000
        private val SONG_ID = Regex("_(\\d{6,})\\.mp3$", RegexOption.IGNORE_CASE)

        /** The search route is documented for the related eu.hitmoz.com site, not live-verified on ru.hitmoz.org. */
        fun searchUrl(query: String): String =
            BASE_URL + "/search?q=" + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8.name())

        /** Pure HTML parser so the site adapter can be tested without making network requests. */
        internal fun parseResults(html: String, artist: String, title: String): List<HitMozTrack> {
            val doc = Jsoup.parse(html, BASE_URL)
            val tracks = doc.select("a[href*=/get/music/]").mapNotNull { anchor ->
                val rawDownload = anchor.absUrl("href")
                if (!isHitMozUrl(rawDownload) || !URI(rawDownload).path.endsWith(".mp3", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val songId = SONG_ID.find(URI(rawDownload).path.substringAfterLast('/'))?.groupValues?.get(1)
                val ancestors = generateSequence(anchor.parent()) { it.parent() }.take(8).toList()
                val row = ancestors.filter { it.select("a[href*=/get/music/]").size == 1 }.lastOrNull()
                    ?: anchor.parent() ?: anchor
                val page = row.selectFirst("a[href*=/song/]")?.absUrl("href")
                    ?.takeIf(::isHitMozUrl)
                    ?: songId?.let { BASE_URL + "/song/" + it }
                    ?: return@mapNotNull null

                val (filenameArtist, filenameTitle) = namesFromFilename(rawDownload)
                val parsedTitle = row.findText(".track__title, .song__title, .track-title, [itemprop=name]")
                val parsedArtist = row.findText(".track__desc, .song__artist, .track__artist, .track-artist, [itemprop=byArtist]")
                val artwork = row.selectFirst(
                    "img.track__img, img.track__image, .track img, img[itemprop=image], img[data-src], img",
                )?.let { image ->
                    image.absUrl("src").takeIf { it.isNotBlank() }
                        ?: image.absUrl("data-src").takeIf { it.isNotBlank() }
                }?.takeIf(::isSafeArtworkUrl)

                HitMozTrack(
                    artist = parsedArtist ?: filenameArtist ?: return@mapNotNull null,
                    title = parsedTitle ?: filenameTitle ?: return@mapNotNull null,
                    duration = row.findText(".track__fulltime, .song__duration, .track__duration, time"),
                    pageUrl = page,
                    downloadUrl = rawDownload,
                    artworkUrl = artwork,
                )
            }.distinctBy { it.downloadUrl }

            return tracks.sortedByDescending { track ->
                val requestedArtist = normalize(artist)
                val requestedTitle = normalize(title)
                val foundArtist = normalize(track.artist)
                val foundTitle = normalize(track.title)
                (if (requestedTitle.isNotEmpty() && foundTitle == requestedTitle) 100 else 0) +
                    (if (requestedArtist.isNotEmpty() && foundArtist.contains(requestedArtist)) 50 else 0) +
                    (if (requestedTitle.isNotEmpty() && foundTitle.contains(requestedTitle)) 15 else 0)
            }.take(30)
        }

        private fun isHitMozUrl(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) &&
                uri.host.equals("ru.hitmoz.org", ignoreCase = true)
        }.getOrDefault(false)

        private fun isSafeArtworkUrl(raw: String): Boolean = runCatching {
            val uri = URI(raw)
            uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
        }.getOrDefault(false)

        private fun namesFromFilename(url: String): Pair<String?, String?> {
            val path = URI(url).rawPath.substringAfterLast('/')
            val filename = URLDecoder.decode(path, StandardCharsets.UTF_8.name())
                .removeSuffix(".mp3").replace(Regex("_(\\d{6,})$"), "")
            val parts = filename.split("_-_", limit = 2)
            if (parts.size != 2) return null to null
            return parts[0].replace('_', ' ').trim().takeIf { it.isNotEmpty() } to
                parts[1].replace('_', ' ').trim().takeIf { it.isNotEmpty() }
        }

        private fun normalize(value: String) =
            value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]"), "")

        private fun Element.findText(selector: String): String? =
            selectFirst(selector)?.text()?.trim()?.takeIf { it.isNotBlank() }
    }
}
