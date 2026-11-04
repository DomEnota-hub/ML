package com.domenota.medialoader.core.diagnostics

import java.net.URI

object AccountBrowserPolicy {
    val rutubeProfileScript = """
        (function(){
          if (location.protocol !== 'https:' || !/(^|\.)rutube\.ru$/i.test(location.hostname)) return false;
          return Array.from(document.querySelectorAll('a,button,[role="button"]'))
            .some(function(el){
              return /^(Выйти|Log out|Sign out)$/i.test((el.innerText || el.getAttribute('aria-label') || '').trim());
            });
        })()
    """.trimIndent()
    fun rutubeProfileConfirmed(scriptResult: String?): Boolean = scriptResult == "true"
    fun allowsRutubeNavigation(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        // Federated sign-in may visit any provider; source recognition stays RUTUBE-only.
        return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    }

    fun isRutubeSite(url: String?): Boolean {
        val uri = runCatching { URI(url ?: return false) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme.equals("https", ignoreCase = true) && uri.rawUserInfo == null &&
            (host == "rutube.ru" || host.endsWith(".rutube.ru"))
    }

    fun canCaptureRutubeSession(url: String?, scriptResult: String?): Boolean =
        isRutubeSite(url) && rutubeProfileConfirmed(scriptResult)

    fun rutubeCookieNames(header: String?): Set<String> = header.orEmpty().split(';')
        .mapNotNull { field -> field.substringBefore('=', "").trim().takeIf {
            field.contains('=') && it.matches(Regex("[A-Za-z0-9_-]{1,80}"))
        } }.toSet()
}
