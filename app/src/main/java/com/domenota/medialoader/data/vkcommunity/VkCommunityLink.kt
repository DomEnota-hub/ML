package com.domenota.medialoader.data.vkcommunity

import java.net.URI
import java.util.Locale

/** Identifies only a community wall, never a post or a personal profile. */
sealed interface VkCommunityLink {
    data class Owner(val id: Long) : VkCommunityLink {
        init { require(id < 0) }
    }
    data class Domain(val name: String) : VkCommunityLink

    companion object {
        private val name = Regex("[A-Za-z][A-Za-z0-9_.]{2,99}")
        private val numeric = Regex("-?[1-9][0-9]{0,17}")

        fun parse(input: String): VkCommunityLink? {
            val raw = input.trim().removePrefix("@")
            if (raw.isEmpty()) return null
            val path = if (raw.contains('/') || raw.contains("://")) {
                val uri = runCatching {
                    URI(if (raw.contains("://")) raw else "https://$raw")
                }.getOrNull() ?: return null
                if (uri.scheme != "https" || uri.userInfo != null ||
                    uri.port !in listOf(-1, 443) ||
                    uri.host?.lowercase(Locale.ROOT) !in setOf(
                        "vk.com", "www.vk.com", "m.vk.com",
                        "vk.ru", "www.vk.ru", "m.vk.ru",
                    )) return null
                uri.path.trim('/')
            } else raw
            if (path.contains('/')) return null
            val owner = when {
                path.startsWith("club") -> path.removePrefix("club")
                path.startsWith("public") -> path.removePrefix("public")
                numeric.matches(path) -> path.removePrefix("-")
                else -> null
            }
            if (owner != null) return owner.toLongOrNull()?.takeIf { it > 0 }
                ?.let { Owner(-it) }
            return if (name.matches(path) && !path.startsWith("id") &&
                !path.startsWith("wall")) Domain(path) else null
        }
    }
}
