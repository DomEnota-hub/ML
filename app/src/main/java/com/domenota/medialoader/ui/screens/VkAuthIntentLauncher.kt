package com.domenota.medialoader.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Sends only a validated QR deep link to VK's official Android package. */
internal object VkAuthIntentLauncher {
    fun open(context: Context, target: String): Boolean = try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
            setPackage("com.vkontakte.android")
            addCategory(Intent.CATEGORY_BROWSABLE)
        })
        true
    } catch (_: Exception) {
        false
    }
}
