package com.domenota.medialoader.ui.components

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Identifies only the expected HitMoz search response, never an unrelated
 * navigation. A search page that lost its 'q' query parameter MUST prove its
 * active form value in the current DOM before results may be accepted.
 */
internal object HitMozSearchPageGate {
    enum class Route { EXACT, NEED_DOM_PROOF, REJECT }

    fun route(expectedUrl: String, actualUrl: String): Route {
        val expected = parse(expectedUrl) ?: return Route.REJECT
        val actual = parse(actualUrl) ?: return Route.REJECT
        if (expected.host != "ru.hitmoz.org" || actual.host != expected.host ||
            expected.path != "/search" || actual.path != "/search"
        ) return Route.REJECT
        val requested = queryParam(expected.rawQuery, "q").orEmpty()
        if (requested.isBlank()) return Route.REJECT
        val observed = queryParam(actual.rawQuery, "q")
        return when {
            observed != null && normalize(requested) == normalize(observed) -> Route.EXACT
            observed == null -> Route.NEED_DOM_PROOF
            else -> Route.REJECT
        }
    }

    /** Same non-empty query, ignoring harmless case and whitespace differences only. */
    fun domQueryMatches(expectedUrl: String, actualFormValue: String): Boolean {
        val expected = parse(expectedUrl) ?: return false
        val q = queryParam(expected.rawQuery, "q").orEmpty()
        return q.isNotBlank() && normalize(q) == normalize(actualFormValue)
    }

    fun expectedQuery(expectedUrl: String): String? =
        parse(expectedUrl)?.let { queryParam(it.rawQuery, "q")?.takeIf(String::isNotBlank) }

    private fun normalize(value: String): String = value.trim().lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")

    private data class SafeUrl(val host: String, val path: String, val rawQuery: String?)

    private fun parse(raw: String): SafeUrl? = runCatching {
        val uri = URI(raw)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            uri.userInfo != null || uri.port !in listOf(-1, 443) || uri.fragment != null
        ) return null
        SafeUrl(uri.host?.lowercase(Locale.ROOT).orEmpty(),
            uri.path.orEmpty().trimEnd('/').ifBlank { "/" },
            uri.rawQuery)
    }.getOrNull()

    private fun queryParam(rawQuery: String?, name: String): String? =
        rawQuery?.split('&')?.firstNotNullOfOrNull { item ->
            val parts = item.split('=', limit = 2)
            if (parts.firstOrNull() != name || parts.size != 2) null
            else runCatching {
                URLDecoder.decode(parts[1], StandardCharsets.UTF_8.name())
            }.getOrNull()
        }
}
