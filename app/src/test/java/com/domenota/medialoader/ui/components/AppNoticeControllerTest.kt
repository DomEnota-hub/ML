package com.domenota.medialoader.ui.components

import android.os.Looper
import java.time.Duration
import com.domenota.medialoader.data.catalog.FileBatchResult
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class AppNoticeControllerTest {
    private val owner = Any()
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun start() { AppNotice.setForeground(owner, true) }
    @After fun stop() { AppNotice.setForeground(owner, false) }
    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Test fun newestTimerWinsAndOldNoticeIsRemoved() {
        AppNotice.post(context, "Первое")
        val first = requireNotNull(AppNotice.state.value.active)
        advance(500)
        AppNotice.post(context, "Второе")
        val second = requireNotNull(AppNotice.state.value.active)
        assertEquals(first.id, AppNotice.state.value.departing?.entry?.id)
        advance(300)
        assertNull(AppNotice.state.value.departing)
        AppNotice.dismiss(first.id)
        assertEquals(second.id, AppNotice.state.value.active?.id)
        advance(second.expiresAt - android.os.SystemClock.uptimeMillis() - 1)
        assertEquals(second.id, AppNotice.state.value.active?.id)
        advance(1)
        assertNull(AppNotice.state.value.active)
    }

    @Test fun batchNoticesKeepActualPartialFailureAndHideOnlyZeroErrors() {
        AppNotice.postBatch(context, "Перенос в личное", FileBatchResult(3, 0), "Перемещено")
        var message = requireNotNull(AppNotice.state.value.active).message
        assertEquals("Перемещено: 3 файла", message.detail)
        assertEquals(NoticeTone.SUCCESS, message.tone)
        AppNotice.postBatch(context, "Перенос в личное", FileBatchResult(1, 2), "Перемещено")
        message = requireNotNull(AppNotice.state.value.active).message
        assertEquals("Перемещено: 1 файл · Ошибок: 2", message.detail)
        assertEquals(NoticeTone.WARNING, message.tone)
        AppNotice.postBatch(context, "Перенос в личное", FileBatchResult(0, 1), "Перемещено")
        assertEquals(NoticeTone.ERROR, AppNotice.state.value.active?.message?.tone)
    }

    @Test fun backgroundClearsAndDoesNotReplaySensitiveOrStaleNotices() {
        AppNotice.post(context, "Файл сохранён")
        assertNotNull(AppNotice.state.value.active)
        AppNotice.setForeground(owner, false)
        assertEquals(NoticeState(), AppNotice.state.value)
        AppNotice.post(context, "Старое сообщение")
        AppNotice.setForeground(owner, true)
        assertEquals(NoticeState(), AppNotice.state.value)
        AppNotice.post(context, "Ошибка https://example.com/private")
        assertFalse(requireNotNull(AppNotice.state.value.active).message.title.contains("example.com"))
    }
}
