package com.domenota.medialoader.data.download

import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Small offline classifier for connectivity failures observed during HitMoz
 * transfers. It never treats a transport error as proof of censorship,
 * a defective CDN, or a specific VPN configuration.
 */
internal object HitMozNetworkFailure {
    enum class Kind(val logName: String) {
        DNS("DNS_LOOKUP_FAILED"),
        TIMEOUT("NETWORK_TIMEOUT"),
        RESET("CONNECTION_RESET"),
        OTHER("OTHER_IO"),
    }

    fun classify(error: Throwable): Kind {
        var cause: Throwable? = error
        var timeout = false
        var reset = false
        val seen = HashSet<Throwable>()
        repeat(12) {
            val current = cause ?: return@repeat
            if (!seen.add(current)) return@repeat
            val description = current.message.orEmpty()
            if (current is UnknownHostException ||
                description.contains("ERR_NAME_NOT_RESOLVED", ignoreCase = true) ||
                description.contains("ERR_DNS_TIMED_OUT", ignoreCase = true) ||
                description.contains("EAI_NONAME", ignoreCase = true)
            ) {
                return Kind.DNS
            }
            if (current is SocketTimeoutException ||
                description.contains("ERR_TIMED_OUT", ignoreCase = true) ||
                description.contains("timed out", ignoreCase = true)
            ) timeout = true
            if ((current is SocketException && description.contains("reset", ignoreCase = true)) ||
                description.contains("ERR_CONNECTION_RESET", ignoreCase = true) ||
                description.contains("Connection reset", ignoreCase = true)
            ) reset = true
            cause = current.cause
        }
        return when {
            timeout -> Kind.TIMEOUT
            reset -> Kind.RESET
            else -> Kind.OTHER
        }
    }

    /** A failed connection, not an HTTP rejection or invalid MP3 response. */
    fun isNetworkFailure(kind: Kind): Boolean = kind != Kind.OTHER

    fun failedMessage(kinds: Set<Kind>): String {
        if (kinds.isEmpty()) return "Не удалось начать загрузку после нескольких попыток. Повторите позже."
        return when {
            kinds == setOf(Kind.DNS) ->
                "Не удалось определить сетевой адрес сервера MP3. Проверьте подключение и попробуйте позже."
            kinds == setOf(Kind.TIMEOUT) ->
                "Сервер MP3 не ответил за отведённое время. Повторите позже."
            kinds == setOf(Kind.RESET) ->
                "Сетевое соединение было сброшено. Попробуйте позже."
            else ->
                "Не удалось подключиться к серверу MP3 после нескольких попыток. Проверьте расширенный журнал."
        }
    }
}
