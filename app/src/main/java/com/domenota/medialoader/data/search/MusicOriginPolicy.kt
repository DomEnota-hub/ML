package com.domenota.medialoader.data.search

import java.net.InetAddress
import java.net.URI
import java.util.Locale

internal enum class MusicOriginProvider(val brand: String, val defaultHost: String, val prefix: String) {
    HITMOZ("hitmoz", "ru.hitmoz.org", "ru"),
    MUZMO("muzmo", "rmr.muzmo.cc", "rmr")
}

/** A search result is a candidate, not an authorization to contact or replace an origin. */
internal object MusicOriginPolicy {
    private val suffixes = setOf("com", "org", "net", "cc", "ru", "su", "me", "io", "site",
        "online", "music", "biz", "info", "pro", "co", "eu", "club", "top", "xyz", "live",
        "to", "fm", "in", "ws", "is", "fun", "space", "world", "tv", "one", "pw", "link", "app",
        "dev", "life", "ai", "us", "uk", "de", "pl", "kz", "by", "ua", "ch", "at", "nl", "fr")
    fun candidateHost(provider: MusicOriginProvider, value: String): String? {
        val host = value.lowercase(Locale.ROOT)
        val parts = host.split('.')
        if (parts.size !in 2..3 || parts[parts.size - 2] != provider.brand ||
            parts.last() !in suffixes ||
            (parts.size == 3 && parts.first() !in setOf(provider.prefix, "www"))) return null
        return host
    }

    fun httpsUri(raw: String): URI? = runCatching {
        val uri = URI(raw)
        uri.takeIf { it.scheme.equals("https", true) && it.host != null &&
            it.userInfo == null && it.port in listOf(-1, 443) && it.fragment == null &&
            it.path == it.normalize().path && !it.path.split('/').any { p -> p == "." || p == ".." } }
    }.getOrNull()

    fun hostOf(provider: MusicOriginProvider, raw: String): String? =
        httpsUri(raw)?.host?.let { candidateHost(provider, it) }

    fun sameHost(first: String, second: String): Boolean =
        httpsUri(first)?.host?.lowercase(Locale.ROOT)?.let { it == httpsUri(second)?.host?.lowercase(Locale.ROOT) } == true

    fun shouldInvestigate(mainFrame: Boolean, activeSearch: Boolean, dnsFailure: Boolean, httpStatus: Int?): Boolean =
        mainFrame && activeSearch && (dnsFailure || httpStatus == 404 || httpStatus == 410)

    fun permanentMigration(provider: MusicOriginProvider, oldHost: String, status: Int, location: String?): String? {
        if (status != 301 && status != 308) return null
        return location?.let { hostOf(provider, it) }?.takeIf { it != oldHost }
    }

    fun publicAddress(ip: InetAddress): Boolean {
        if (ip.isAnyLocalAddress || ip.isLoopbackAddress || ip.isLinkLocalAddress ||
            ip.isSiteLocalAddress || ip.isMulticastAddress) return false
        val b = ip.address.map { it.toInt() and 255 }
        if (b.size == 4) return !(b[0] == 0 || b[0] == 127 || b[0] >= 224 ||
            (b[0] == 100 && b[1] in 64..127) || (b[0] == 169 && b[1] == 254) ||
            (b[0] == 192 && b[1] == 0) || (b[0] == 192 && b[1] == 168) ||
            (b[0] == 198 && b[1] in 18..19) || (b[0] == 198 && b[1] == 51 && b[2] == 100) ||
            (b[0] == 203 && b[1] == 0 && b[2] == 113))
        // Only ordinary global unicast; reject ULA, mapped addresses, link-local, etc.
        return b.size == 16 && (b[0] and 0xe0) == 0x20 &&
            !(b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && b[3] == 0xb8)
    }
}
