package com.domenota.medialoader.core.diagnostics

import java.net.URI

object AccountBrowserPolicy {
    val rutubeProfileScript = """
        (function(){
          return Array.from(document.querySelectorAll('a,button,[role="button"]'))
            .some(function(el){
              return /^(Выйти|Log out|Sign out)$/i.test((el.innerText || el.getAttribute('aria-label') || '').trim());
            });
        })()
    """.trimIndent()
    fun rutubeProfileConfirmed(scriptResult: String?): Boolean = scriptResult == "true"
    fun allowsRutubeNavigation(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        return host == "rutube.ru" || host.endsWith(".rutube.ru") ||
            host == "gid.ru" || host.endsWith(".gid.ru")
    }

    fun rutubeCookieNames(header: String?): Set<String> = header.orEmpty().split(';')
        .mapNotNull { field -> field.substringBefore('=', "").trim().takeIf {
            field.contains('=') && it.matches(Regex("[A-Za-z0-9_-]{1,80}"))
        } }.toSet()
}
