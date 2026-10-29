package com.domenota.medialoader.ui.screens

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/** Upgrade only VK's observed QR-auth hop; never send login data over plain HTTP. */
internal object VkAuthNavigationRecovery {
    private const val OFFICIAL_PACKAGE = "com.vkontakte.android"

    /** Return only a VK QR deep link intended for the official Android app. */
    fun officialAppTarget(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("intent", true) ||
            !uri.host.equals("qr.vk.ru", true) || uri.userInfo != null || uri.port != -1)
            return null
        val parts = uri.rawFragment?.split(';').orEmpty()
        if (parts.firstOrNull() != "Intent" || parts.lastOrNull() != "end" ||
            parts.none { it == "scheme=vk" || it == "scheme=https" }) return null
        val packages = parts.filter { it.startsWith("package=") }.map { it.substringAfter('=') }
        if (packages.size > 1 || packages.singleOrNull()?.let { it != OFFICIAL_PACKAGE } == true)
            return null
        val scheme = if (parts.any { it == "scheme=https" }) "https" else "vk"
        val target = "$scheme:" + raw.substringAfter(':').substringBefore("#Intent;")
        val parsed = runCatching { URI(target) }.getOrNull() ?: return null
        return target.takeIf { parsed.host.equals("qr.vk.ru", true) &&
            parsed.userInfo == null && parsed.port == -1 }
    }

    fun safeIntentSummary(raw: String): String {
        val fragment = runCatching { URI(raw).rawFragment }.getOrNull().orEmpty()
        val fields = fragment.split(';')
        val declared = fields.firstOrNull { it.startsWith("scheme=") }?.substringAfter('=')
        val scheme = when (declared) {
            "vk", "https" -> declared
            null -> "none"
            else -> "other"
        }
        val pkg = fields.firstOrNull { it.startsWith("package=") }?.substringAfter('=')
        val packageKind = when (pkg) {
            OFFICIAL_PACKAGE -> "official"
            null -> "none"
            else -> "other"
        }
        val fallback = fields.any { it.startsWith("S.browser_fallback_url=") }
        return "declaredScheme=$scheme · package=$packageKind · fallback=$fallback"
    }

    fun secureTarget(raw: String): String? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.host.equals("qr.vk.ru", true) || uri.userInfo != null) return null
        if (uri.scheme.equals("http", true))
            return "https" + raw.substring(raw.indexOf(':'))
        if (!uri.scheme.equals("intent", true)) return null
        val parts = uri.rawFragment?.split(';').orEmpty()
        if (parts.firstOrNull() != "Intent" || parts.lastOrNull() != "end") return null
        val fallback = parts.firstOrNull { it.startsWith("S.browser_fallback_url=") }
            ?.substringAfter('=')?.let {
                runCatching { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }.getOrNull()
            }
        if (fallback != null) return fallback.takeIf(::trustedHttps)
        // An intent whose declared web scheme is HTTPS can stay inside this WebView.
        if (parts.none { it == "scheme=https" }) return null
        return "https:" + raw.substringAfter(':').substringBefore("#Intent;")
    }

    private fun trustedHttps(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", true) && uri.userInfo == null &&
            listOf("vk.ru", "vk.com", "vkid.ru").any { host == it || host.endsWith(".$it") }
    }
}
