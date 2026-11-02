package com.domenota.medialoader.ui.model

/**
 * Source/type chips are single-choice filters with an explicit "All" state.
 * A second tap on the active chip returns to "All"; never invert that
 * chip and accidentally show every other source.
 */
internal fun <T> exclusiveFilterClick(all: Set<T>, enabled: Set<T>, chosen: T): Set<T> {
    require(chosen in all) { "The selected filter must be visible" }
    return if (enabled.size == 1 && chosen in enabled) all else setOf(chosen)
}

/** Russian file counts for the trash header, including 11-14 exceptions. */
internal fun russianFileCount(count: Int): String {
    require(count >= 0)
    val word = when {
        count % 100 in 11..14 -> "файлов"
        count % 10 == 1 -> "файл"
        count % 10 in 2..4 -> "файла"
        else -> "файлов"
    }
    return "$count $word"
}
