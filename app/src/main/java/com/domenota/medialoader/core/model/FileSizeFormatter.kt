package com.domenota.medialoader.core.model

import java.util.Locale

/** Human-readable preview size. Uses binary thresholds and the user's requested labels. */
object FileSizeFormatter {
    private const val KIB = 1024.0
    private const val MIB = KIB * KIB

    fun format(bytes: Long?): String {
        if (bytes == null) return "Размер неизвестен"
        require(bytes >= 0) { "sizeBytes must be nonnegative" }
        if (bytes == 0L) return "0 КБ"
        val (size, unit) = if (bytes < MIB) bytes / KIB to "КБ" else bytes / MIB to "МБ"
        return String.format(Locale.US, "%.1f %s", size, unit)
    }
}
