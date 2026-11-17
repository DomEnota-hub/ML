package com.domenota.medialoader.ui.components

internal enum class NoticeTone { INFO, SUCCESS, WARNING, ERROR }

internal data class NoticeMessage(val title: String, val detail: String? = null, val tone: NoticeTone = NoticeTone.INFO)
internal data class NoticeEntry(val id: Long, val message: NoticeMessage, val shownAt: Long, val holdMillis: Long) {
    val fadeAt: Long get() = shownAt + NoticePolicy.TRANSITION_MS + holdMillis
    val expiresAt: Long get() = fadeAt + NoticePolicy.TRANSITION_MS
}
internal data class DepartingNotice(val entry: NoticeEntry, val startedAt: Long)
internal data class NoticeState(val active: NoticeEntry? = null, val departing: DepartingNotice? = null)

internal object NoticePolicy {
    const val HOLD_MS = 2000L
    const val TRANSITION_MS = 300L

    fun publish(state: NoticeState, next: NoticeEntry): NoticeState =
        NoticeState(next, state.active?.takeIf { next.shownAt < it.expiresAt }?.let { DepartingNotice(it, next.shownAt) })

    fun dismiss(state: NoticeState, id: Long): NoticeState =
        if (state.active?.id == id) NoticeState() else state

    fun removeDeparting(state: NoticeState, id: Long): NoticeState =
        if (state.departing?.entry?.id == id) state.copy(departing = null) else state

    fun classify(text: String): NoticeTone {
        val value = text.lowercase()
        return when {
            // A stopped transfer is not a success even when its copies were saved.
            "перенос остановлен" in value || "перенос был прерван" in value -> NoticeTone.WARNING
            listOf("не удалось", "ошибка", "ошибок:").any(value::contains) -> NoticeTone.ERROR
            listOf("недоступ", "нет доступа", "прерван", "разрешите", "сначала").any(value::contains) -> NoticeTone.WARNING
            listOf("сохранён", "сохранен", "сохранена", "скопирован", "добавлено", "обновлено", "установлены", "импортированы").any(value::contains) -> NoticeTone.SUCCESS
            else -> NoticeTone.INFO
        }
    }
}
