package com.domenota.medialoader.ui.components

import org.junit.Assert.*
import org.junit.Test

class NoticePolicyTest {
    private fun entry(id: Long, time: Long) = NoticeEntry(id, NoticeMessage("Message $id"), time, NoticePolicy.HOLD_MS)

    @Test fun holdsTwoSecondsAfterEntranceThenFades() {
        val value = entry(1, 1000)
        assertEquals(3300L, value.fadeAt)
        assertEquals(3600L, value.expiresAt)
    }

    @Test fun newestDisplacesCurrentAndNeverQueuesBursts() {
        var state = NoticeState()
        for (id in 1L..20L) {
            state = NoticePolicy.publish(state, entry(id, id * 10L))
            assertEquals(id, state.active?.id)
            assertEquals(if (id == 1L) null else id - 1L, state.departing?.entry?.id)
        }
        assertEquals(20L, state.active?.id)
        assertEquals(19L, state.departing?.entry?.id)
        assertEquals(state, NoticePolicy.dismiss(state, 1))
        assertEquals(state, NoticePolicy.removeDeparting(state, 18))
        assertNull(NoticePolicy.removeDeparting(state, 19).departing)
        assertEquals(NoticeState(), NoticePolicy.dismiss(state, 20))
    }

    @Test fun fadingNoticeCanBeDisplacedButExpiredNoticeCannotReturn() {
        val first = entry(1, 1000)
        val state = NoticePolicy.publish(NoticeState(first), entry(2, 3450))
        assertEquals(1L, state.departing?.entry?.id)
        assertNull(NoticePolicy.publish(NoticeState(first), entry(3, 3600)).departing)
    }

    @Test fun distinctOutcomeTones() {
        assertEquals(NoticeTone.SUCCESS, NoticePolicy.classify("Файл скопирован"))
        assertEquals(NoticeTone.WARNING, NoticePolicy.classify("Проверка новой копии: перенос остановлен. План и проверенные копии сохранены."))
        assertEquals(NoticeTone.WARNING, NoticePolicy.classify("Перенос был прерван. Проверьте файлы"))
        assertEquals(NoticeTone.WARNING, NoticePolicy.classify("Файл недоступен"))
        assertEquals(NoticeTone.ERROR, NoticePolicy.classify("Не удалось сохранить лог"))
        assertEquals(NoticeTone.INFO, NoticePolicy.classify("Ищу название трека…"))
    }
}
