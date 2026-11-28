package com.domenota.medialoader.data.editor
import org.junit.Assert.*
import org.junit.Test
class EditorExportNotificationsTest {
    @Test fun staleCancelNeverAppliesToNewOperationOrProtectedOriginalWrite() {
        val first=EditorExportState(exporting=true)
        val next=EditorExportState(exporting=true)
        assertTrue(first.canCancelNotification(first.notificationNonce))
        assertFalse(next.canCancelNotification(first.notificationNonce))
        assertFalse(first.copy(exporting=false).canCancelNotification(first.notificationNonce))
        assertFalse(first.copy(operation="Запись оригинала").canCancelNotification(first.notificationNonce))
    }
    @Test fun batchOutcomeIsTypedAndExceptionContentsNeverBecomeNotificationText() {
        val result=EditorExportState(operation="Пакетная обработка фото",notificationOutcome=EditorExportOutcome.COMPLETE,
            notificationTotal=3,notificationSaved=3,message="secret content://private/track")
        assertTrue(editorNotificationMessage(result).first.contains("Готово"))
        assertEquals("Сохранено: 3 из 3 · ошибок: 0",editorNotificationMessage(result).second)
        val failed=editorNotificationMessage(EditorExportState(message="/private/secret.mp3"))
        assertFalse(failed.second.contains("secret"))
        assertFalse(failed.second.contains("private"))
    }
}
