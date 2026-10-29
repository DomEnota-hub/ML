package com.domenota.medialoader.data

object InstagramSessionCookiePolicy {
    fun hasSession(header: String): Boolean = header.split(';').any { part ->
        val cookie = part.trim()
        cookie.startsWith("sessionid=") && cookie.substringAfter('=').isNotBlank()
    }
}
