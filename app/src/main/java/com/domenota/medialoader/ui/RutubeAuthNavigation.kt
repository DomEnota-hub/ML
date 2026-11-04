package com.domenota.medialoader.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.domenota.medialoader.core.diagnostics.AccountBrowserPolicy

/** Provider-independent app handoff. Rebuild ACTION_VIEW; never execute supplied components/extras. */
internal object RutubeAuthNavigation {
    fun appUri(address: String): Uri? = runCatching {
        val uri = if (address.startsWith("intent:", true))
            Intent.parseUri(address, Intent.URI_INTENT_SCHEME).data else Uri.parse(address)
        val scheme = uri?.scheme?.lowercase() ?: return null
        if (scheme in setOf("http","https","file","content","data","javascript","blob","about","intent")) null else uri
    }.getOrNull()
    fun webFallback(address: String): String? = runCatching {
        if (!address.startsWith("intent:",true)) return null
        val intent = Intent.parseUri(address,Intent.URI_INTENT_SCHEME)
        intent.getStringExtra("browser_fallback_url")?.takeIf(AccountBrowserPolicy::allowsRutubeNavigation)
            ?: intent.dataString?.takeIf(AccountBrowserPolicy::allowsRutubeNavigation)
    }.getOrNull()
    fun open(context: Context, address: String): Boolean {
        val uri = appUri(address) ?: return false
        return runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW,uri).addCategory(Intent.CATEGORY_BROWSABLE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));true
        }.getOrDefault(false)
    }
}
