package com.domenota.medialoader.data.share

import android.content.Context
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramLinkParser

/** Changes only Android's Share action; manual paste and other sources stay interactive. */
object InstagramShareSettings {
    private const val PREFS = "ui"
    private const val KEY = "instagram_share_mode"

    fun isAutomatic(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "manual") == "automatic"

    fun setAutomatic(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, if (enabled) "automatic" else "manual").apply()
    }
}

/** Select every visual element in order; never queue any separately extracted music. */
object InstagramAutoShareSelection {
    fun canonicalUrl(raw: String): String? =
        InstagramLinkParser.parse(raw)?.let { link ->
            if (link.isStory) raw.trim().substringBefore('?') else link.pageUrl()
        }

    fun visuals(items: List<MediaItem>): List<MediaItem> =
        items.filter { it.providerId == "instagram" &&
            (it.type == MediaType.PHOTO || it.type == MediaType.VIDEO) }
}
