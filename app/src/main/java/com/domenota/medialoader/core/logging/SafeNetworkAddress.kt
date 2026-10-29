package com.domenota.medialoader.core.logging

import java.net.URI
import java.security.MessageDigest

/**
 * Network diagnostics safe for the user-exported log: the host and a coarse path kind are
 * useful while signed paths and query values never leave the request pipeline.
 */
object SafeNetworkAddress {
    fun describe(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return "address=invalid"
        val path = uri.path.orEmpty()
        val pathKind = path.split('/').filter(String::isNotBlank).firstOrNull()
            ?.take(32)?.lowercase() ?: "root"
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "host=${uri.host.orEmpty().lowercase()} · path=$pathKind · query=${!uri.rawQuery.isNullOrEmpty()} · urlId=$fingerprint"
    }
}
