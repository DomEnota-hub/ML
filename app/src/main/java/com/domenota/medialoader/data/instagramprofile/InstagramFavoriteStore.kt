package com.domenota.medialoader.data.instagramprofile

import android.content.Context
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

data class InstagramFavorite(
    val username: String, val label: String, val avatarUrl: String?,
    val sinceDate: String, val includeReels: Boolean, val reviewedThrough: Long = 0L,
)

class InstagramFavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("instagram_profile_favorites", 0)

    fun list(): List<InstagramFavorite> = decode(prefs.getString("items", "[]").orEmpty())

    fun save(profile: InstagramProfileInfo, since: LocalDate, includeReels: Boolean) {
        val old = list()
        val updated = InstagramFavorite(profile.username, profile.name, profile.avatarUrl,
            since.toString(), includeReels, old.firstOrNull { it.username == profile.username }?.reviewedThrough ?: 0L)
        write(old.filterNot { it.username == profile.username } + updated)
    }

    fun remove(username: String) = write(list().filterNot { it.username == username })

    fun markReviewed(username: String, through: Long) = write(list().map { favorite ->
        if (favorite.username == username) favorite.copy(reviewedThrough = maxOf(favorite.reviewedThrough, through))
        else favorite
    })

    private fun write(items: List<InstagramFavorite>) {
        prefs.edit().putString("items", encode(items)).apply()
    }

    companion object {
        fun encode(items: List<InstagramFavorite>): String = JSONArray(items.map { item -> JSONObject().apply {
            put("username", item.username); put("label", item.label)
            put("avatar", item.avatarUrl); put("since", item.sinceDate)
            put("reels", item.includeReels); put("reviewed", item.reviewedThrough)
        } }).toString()

        fun decode(raw: String): List<InstagramFavorite> = runCatching {
            val json = JSONArray(raw)
            (0 until json.length()).mapNotNull { index ->
                val item = json.optJSONObject(index) ?: return@mapNotNull null
                val username = item.optString("username")
                val since = item.optString("since")
                if (InstagramProfileLink.parse(username)?.username != username ||
                    runCatching { LocalDate.parse(since) }.isFailure) return@mapNotNull null
                InstagramFavorite(username, item.optString("label").ifBlank { username },
                    item.optString("avatar").takeIf { it.startsWith("https://") }, since,
                    item.optBoolean("reels"), item.optLong("reviewed"))
            }
        }.getOrDefault(emptyList())
    }
}
