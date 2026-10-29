package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class VkFavorite(val key: String, val label: String, val link: String,
    val avatarUrl: String? = null, val sinceDate: String = LocalDate.now().toString(),
    val reviewedThroughEpoch: Long? = null)

/** Community shortcuts only; credentials are kept in VkTokenStore. */
class VkFavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vk_community_favorites", 0)

    fun list(): List<VkFavorite> = decode(prefs.getString("items", "[]").orEmpty())

    fun save(link: String, label: String, avatarUrl: String? = null): Boolean {
        val key = key(link) ?: return false
        val normalized = canonicalLink(link) ?: return false
        val name = label.trim().ifBlank { normalized.substringAfterLast('/') }.take(80)
        val previous = list().firstOrNull { it.key == key }
        val avatar = avatarUrl?.takeIf {
            com.domenota.medialoader.data.preview.VkThumbnailPolicy.isAllowedUrl(it)
        } ?: previous?.avatarUrl
        val next = list().filterNot { it.key == key } + VkFavorite(key, name, normalized, avatar,
            previous?.sinceDate ?: LocalDate.now().toString(), previous?.reviewedThroughEpoch)
        prefs.edit().putString("items", encode(next.takeLast(100))).apply()
        return true
    }

    fun remove(key: String) {
        prefs.edit().putString("items", encode(list().filterNot { it.key == key })).apply()
    }

    fun setSinceDate(key: String, date: LocalDate): Boolean {
        if (date.isAfter(LocalDate.now())) return false
        val current = list()
        if (current.none { it.key == key }) return false
        return prefs.edit().putString("items", encode(current.map {
            if (it.key == key) withSinceDate(it, date) else it
        })).commit()
    }

    fun markReviewed(key: String, throughEpoch: Long): Boolean {
        val current = list()
        if (current.none { it.key == key }) return false
        return prefs.edit().putString("items", encode(current.map {
            if (it.key == key) markReviewed(it, throughEpoch) else it
        })).commit()
    }

    fun enrich(infos: Map<String, VkCommunityInfo>): List<VkFavorite> {
        val current = list()
        val updated = mergeMetadata(current, infos)
        if (updated != current) prefs.edit().putString("items", encode(updated)).apply()
        return updated
    }

    companion object {
        internal fun withSinceDate(favorite: VkFavorite, date: LocalDate): VkFavorite =
            if (favorite.sinceDate == date.toString()) favorite else favorite.copy(
                sinceDate = date.toString(), reviewedThroughEpoch = null)

        internal fun markReviewed(favorite: VkFavorite, throughEpoch: Long): VkFavorite =
            favorite.copy(reviewedThroughEpoch = maxOf(favorite.reviewedThroughEpoch ?: 0L,
                throughEpoch))

        internal fun mergeMetadata(items: List<VkFavorite>, infos: Map<String, VkCommunityInfo>) =
            items.map { favorite ->
                val info = infos[favorite.key] ?: return@map favorite
                val slug = favorite.link.substringAfterLast('/')
                favorite.copy(
                    label = if (favorite.label.equals(slug, ignoreCase = true)) info.name
                        else favorite.label,
                    avatarUrl = favorite.avatarUrl ?: info.avatarUrl,
                )
            }

        fun key(link: String): String? = when (val parsed = VkCommunityLink.parse(link)) {
            is VkCommunityLink.Owner -> "owner:${parsed.id}"
            is VkCommunityLink.Domain -> "domain:${parsed.name.lowercase(Locale.ROOT)}"
            null -> null
        }

        fun canonicalLink(link: String): String? = when (val parsed = VkCommunityLink.parse(link)) {
            is VkCommunityLink.Owner -> "https://vk.com/club${-parsed.id}"
            is VkCommunityLink.Domain -> "https://vk.com/${parsed.name.lowercase(Locale.ROOT)}"
            null -> null
        }

        fun encode(items: List<VkFavorite>): String = JSONArray().apply {
            items.forEach { favorite -> put(JSONObject().put("key", favorite.key)
                .put("label", favorite.label).put("link", favorite.link)
                .put("avatar_url", favorite.avatarUrl).put("since_date", favorite.sinceDate)
                .put("reviewed_through", favorite.reviewedThroughEpoch)) }
        }.toString()

        fun decode(raw: String, today: LocalDate = LocalDate.now()): List<VkFavorite> = runCatching {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val link = obj.optString("link")
                    val key = key(link) ?: continue
                    val avatar = obj.optString("avatar_url").takeIf {
                        com.domenota.medialoader.data.preview.VkThumbnailPolicy.isAllowedUrl(it)
                    }
                    val since = obj.optString("since_date").let { rawDate ->
                        runCatching { LocalDate.parse(rawDate) }.getOrNull()
                            ?.takeUnless { it.isAfter(today) } ?: today
                    }
                    val reviewed = obj.optLong("reviewed_through").takeIf { it > 0 }
                    add(VkFavorite(key, obj.optString("label").take(80), canonicalLink(link)!!,
                        avatar, since.toString(), reviewed))
                }
            }.distinctBy { it.key }
        }.getOrDefault(emptyList())
    }
}
