package com.domenota.medialoader.data.tasks

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TaskNotificationsTest {
    private fun row(status:TaskStatus=TaskStatus.RUNNING,wait:TaskWait?=null)=TaskSnapshot(
        taskId(TaskOwner.WAVEFORM,"fixture"),TaskKind.WAVEFORM,status,files=TaskProgress(7,10),wait=wait)
    @Test fun realCountersDistinguishCacheSkipsErrorsAndPausedWaitingStates() {
        val partial=row(TaskStatus.PARTIAL).copy(errors=1,details=TaskDetails(outcomes=TaskOutcomes(5,1,0,1),failure=TaskFailure.IO))
        val message=TaskNotifications.message(partial)
        assertTrue(message.text.contains("Из кеша: 5 · создано: 1"))
        assertTrue(message.text.contains("Пропущено: 0 · ошибок: 1"))
        assertNull(message.percent)
        assertEquals(70,TaskNotifications.message(row()).percent)
        assertTrue(TaskNotifications.message(row(TaskStatus.WAITING,TaskWait.CHARGING)).title.contains("Зарядка"))
        assertTrue(TaskNotifications.message(row(TaskStatus.PAUSED)).title.contains("Пауза"))
        assertTrue(TaskNotifications.message(row(TaskStatus.PARTIAL)).title.contains("пропусками"))
        assertFalse(TaskNotifications.message(row(TaskStatus.COMPLETE)).title.contains("ошиб"))
    }
    @Test fun iconsAreDistinctAndCompletionHasOnlyOneOwner() {
        assertEquals(TaskKind.entries.size,TaskKind.entries.map(TaskNotifications::icon).toSet().size)
        assertFalse(TaskNotifications.completionOwned(TaskKind.EXPORT))
        assertFalse(TaskNotifications.completionOwned(TaskKind.BATCH_EDIT))
        assertTrue(TaskNotifications.completionOwned(TaskKind.OCR))
    }
    @Test fun notificationOnlyAcceptsOpaqueTaskIdsAndConsumptionIsRequestBound() {
        assertFalse(TaskNotificationNavigation.accept(Intent("other")))
        assertFalse(TaskNotificationNavigation.accept(Intent(TaskNotifications.OPEN_TASK).putExtra(TaskNotifications.EXTRA_TASK,"content://private")))
        assertTrue(TaskNotificationNavigation.accept(Intent(TaskNotifications.OPEN_TASK).putExtra(TaskNotifications.EXTRA_TASK,row().id)))
        val first=requireNotNull(TaskNotificationNavigation.request.value)
        assertTrue(TaskNotificationNavigation.accept(Intent(TaskNotifications.OPEN_TASK)))
        val second=requireNotNull(TaskNotificationNavigation.request.value)
        TaskNotificationNavigation.consume(first)
        assertEquals(second,TaskNotificationNavigation.request.value)
        TaskNotificationNavigation.consume(second)
        assertNull(TaskNotificationNavigation.request.value)
    }
}
