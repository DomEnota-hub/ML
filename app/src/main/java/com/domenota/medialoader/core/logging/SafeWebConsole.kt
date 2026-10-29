package com.domenota.medialoader.core.logging

import android.net.Uri
import android.webkit.ConsoleMessage

/**
 * Report web console events without storing arbitrary third-party page messages.
 * Page scripts may print cookies, auth tokens or private messages.
 */
object SafeWebConsole {
    fun describe(event: ConsoleMessage): String {
        val host = runCatching { Uri.parse(event.sourceId()).host }.getOrNull().orEmpty().take(80)
        val message = event.message().orEmpty()
        val category = when {
            message.contains("TypeError", ignoreCase = true) -> "TypeError"
            message.contains("ReferenceError", ignoreCase = true) -> "ReferenceError"
            message.contains("SyntaxError", ignoreCase = true) -> "SyntaxError"
            message.contains("SecurityError", ignoreCase = true) -> "SecurityError"
            message.contains("NetworkError", ignoreCase = true) -> "NetworkError"
            else -> "other"
        }
        return "JS console · level=${event.messageLevel()} · line=${event.lineNumber()} · scriptHost=$host · type=$category · chars=${message.length}"
    }
}
