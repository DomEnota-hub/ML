package com.domenota.medialoader.core.logging

import android.content.Context
import android.os.Build
import com.domenota.medialoader.BuildConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Small persistent diagnostic log intended for user-exported bug reports.
 * Secrets are never deliberately logged; common cookie/session values are redacted as a final guard.
 */
object AppLog {
    private const val MAX_BYTES = 4L * 1024L * 1024L
    private const val MAX_ARCHIVES = 4
    private const val TAIL_LINES = 2500
    private val lock = Any()
    private val sampled = mutableMapOf<String, Pair<Long, Int>>()
    private val operationCounter = AtomicLong()
    private val timestamp = ThreadLocal.withInitial {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
    private val secretPattern = Regex(
        "(?i)(sessionid|sapisid|apisid|__Secure-1PAPISID|__Secure-3PAPISID|sid|hsid|ssid)=([^;\\s]+)",
    )

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var crashHandlerInstalled = false
    @Volatile
    private var sessionLogged = false
    @Volatile
    private var extended = false

    fun init(context: Context) {
        val applicationContext = context.applicationContext
        if (appContext == null) synchronized(lock) {
            if (appContext == null) {
                appContext = applicationContext
                extended = applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)
                    .getBoolean("extended_app_log", false)
            }
        }
        installCrashHandler()
        if (!sessionLogged) synchronized(lock) {
            if (!sessionLogged) {
                sessionLogged = true
                i(
                    "App",
                    "Session started · MediaLoader ${BuildConfig.VERSION_NAME} " +
                        "(${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE}",
                )
            }
        }
    }

    fun d(tag: String, message: String) {
        if (extended) append("DEBUG", tag, message, null)
    }
    fun verbose(tag: String, message: String) = d(tag, message)
    /** Sample repetitive network events by operation and host; keep errors unsampled. */
    fun verboseSampled(tag: String, key: String, message: String) {
        if (!extended) return
        val now = android.os.SystemClock.elapsedRealtime()
        var output: String? = null
        synchronized(lock) {
            val previous = sampled[key]
            if (previous == null || now - previous.first >= 15_000L) {
                output = if (previous != null && previous.second > 0)
                    "$message · repeatedSuppressed=${previous.second}" else message
                if (sampled.size > 256) sampled.clear()
                sampled[key] = now to 0
            } else {
                sampled[key] = previous.first to (previous.second + 1)
            }
        }
        output?.let { append("DEBUG", tag, it, null) }
    }

    fun isExtended(): Boolean = extended

    /** A short in-process correlation id for a user action; it contains no URL or account data. */
    fun newOperationId(prefix: String): String =
        "$prefix-" + operationCounter.incrementAndGet().toString(36)
    fun setExtended(enabled: Boolean) {
        if (extended == enabled) return
        synchronized(lock) {
            appContext?.getSharedPreferences("ui", Context.MODE_PRIVATE)?.edit()
                ?.putBoolean("extended_app_log", enabled)?.apply()
            extended = enabled
        }
        i("Logs", "Extended diagnostics " + if (enabled) "enabled" else "disabled")
    }
    fun i(tag: String, message: String) = append("INFO", tag, message, null)
    fun ok(tag: String, message: String) = append("OK", tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = append("WARN", tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = append("ERROR", tag, message, error)

    fun readText(): String = synchronized(lock) {
        val file = logFile() ?: return@synchronized ""
        runCatching {
            val all = logFiles(file)
            all.flatMap { it.readLines().takeLast(TAIL_LINES) }.takeLast(TAIL_LINES)
                .joinToString("\n")
        }.getOrElse { "Не удалось прочитать журнал: ${it.message}" }
    }

    fun clear() = synchronized(lock) {
        val file = logFile() ?: return@synchronized
        runCatching { logFiles(file).forEach { it.delete() } }
    }

    fun exportMarkdown(includeExplanations: Boolean = false): String {
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())
        val rawBody = synchronized(lock) {
            val file = logFile()
            if (file == null) "" else runCatching {
                logFiles(file).joinToString("\n") { it.readText() }
            }.getOrDefault("")
        }.ifBlank { "Журнал пуст." }
        val body = if (includeExplanations) LogExplanation.annotate(rawBody) else rawBody
        return buildString {
            appendLine("# MediaLoader — журнал приложения")
            appendLine()
            appendLine("- Создан: $now")
            appendLine("- Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("- Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("- Расширенный журнал на момент экспорта: ${if (extended) "включён" else "выключен"}")
            appendLine("- Пояснения: ${if (includeExplanations) "включены" else "выключены"}")
            appendLine()
            appendLine("> Журнал формируется локально. Значения известных cookie/session-полей автоматически скрываются.")
            appendLine()
            appendLine("```text")
            append(body.replace("```", "` ` `"))
            if (!body.endsWith('\n')) appendLine()
            appendLine("```")
        }
    }

    private fun append(level: String, tag: String, message: String, error: Throwable?) {
        val file = logFile() ?: return
        val safeMessage = redact(message)
        val safeStack = error?.stackTraceToString()?.let(::redact)
        synchronized(lock) {
            runCatching {
                file.parentFile?.mkdirs()
                file.appendText(buildString {
                    append(timestamp.get().format(Date()))
                    append(" [").append(level).append("] [").append(tag).append("] ")
                    appendLine(safeMessage)
                    if (!safeStack.isNullOrBlank()) {
                        safeStack.lineSequence().take(80).forEach { appendLine("    $it") }
                    }
                })
                trimIfNeeded(file)
            }
        }
    }

    private fun redact(value: String): String = secretPattern.replace(value) { match ->
        "${match.groupValues[1]}=<redacted>"
    }

    private fun logFile(): File? = appContext?.let { File(it.filesDir, "logs/medialoader.log") }

    private fun logFiles(file: File): List<File> =
        (MAX_ARCHIVES downTo 1).map { File(file.parentFile, "${file.name}.$it") }
            .plus(file).filter { it.exists() }

    private fun trimIfNeeded(file: File) {
        if (file.length() <= MAX_BYTES) return
        File(file.parentFile, "${file.name}.$MAX_ARCHIVES").delete()
        for (index in MAX_ARCHIVES - 1 downTo 1) {
            val from = File(file.parentFile, "${file.name}.$index")
            val to = File(file.parentFile, "${file.name}.${index + 1}")
            if (from.exists()) from.renameTo(to)
        }
        file.renameTo(File(file.parentFile, "${file.name}.1"))
    }

    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        synchronized(lock) {
            if (crashHandlerInstalled) return
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                e("Crash", "Uncaught exception on thread ${thread.name}", throwable)
                previous?.uncaughtException(thread, throwable)
            }
            crashHandlerInstalled = true
        }
    }
}
