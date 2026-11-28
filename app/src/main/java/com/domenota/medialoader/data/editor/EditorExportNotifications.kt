package com.domenota.medialoader.data.editor

internal fun EditorExportState.canCancelNotification(nonce:String?):Boolean =
    exporting && nonce==notificationNonce && operation!="Запись оригинала"

/** Exception messages belong in diagnostics, never on the lock screen. */
internal fun editorNotificationMessage(state:EditorExportState):Pair<String,String> {
    val outcome=state.notificationOutcome ?: if(state.completedItemId!=null)EditorExportOutcome.COMPLETE else EditorExportOutcome.FAILED
    val title=state.operation+" · "+when(outcome){
        EditorExportOutcome.COMPLETE->"Готово"
        EditorExportOutcome.PARTIAL->"Частично завершено"
        EditorExportOutcome.FAILED->"Ошибка"
        EditorExportOutcome.CANCELLED->"Отменено"
    }
    val body=state.notificationTotal?.let{"Сохранено: ${state.notificationSaved} из $it · ошибок: ${state.notificationFailed}"}
        ?: when(outcome){
            EditorExportOutcome.COMPLETE->state.outputDetails ?: "Файл сохранён"
            EditorExportOutcome.CANCELLED->"Обработка отменена"
            else->"Не удалось завершить обработку"
        }
    return title to body
}
