package com.domenota.medialoader.ui.components

internal fun deleteSelectionTitle(count: Int): String = when {
    count == 1 -> "Удалить выбранный объект?"
    count % 100 in 11..14 -> "Удалить выбранные $count объектов?"
    count % 10 == 1 -> "Удалить выбранный $count объект?"
    count % 10 in 2..4 -> "Удалить выбранные $count объекта?"
    else -> "Удалить выбранные $count объектов?"
}
