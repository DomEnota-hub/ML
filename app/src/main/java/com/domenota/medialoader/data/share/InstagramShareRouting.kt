package com.domenota.medialoader.data.share

import android.content.Context
import android.content.Intent
import com.domenota.medialoader.ui.extractFirstUrl

/** Shared by the normal receiver and legacy explicit MainActivity share targets. */
internal object InstagramShareRouting {
    const val RETURN_TO_SOURCE = "com.domenota.medialoader.RETURN_TO_SHARE_SOURCE"
    fun automaticUrl(context: Context, intent: Intent?): String? {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain" ||
            !InstagramShareSettings.isAutomatic(context)) return null
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return null
        return extractFirstUrl(text)?.let(InstagramAutoShareSelection::canonicalUrl)
    }
}
