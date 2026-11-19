package com.domenota.medialoader.data.catalog.visibility

/**
 * 1.7.206 block 1: no new physical HIDE/SHOW batch may be started or resumed.
 *
 * Old journals, receipts, owner markers, and hidden files are retained for
 * read-only recovery diagnostics. The replacement .nomedia/SAF visibility
 * engine must not invoke the old file transport.
 *
 * This policy is checked by the UI, foreground coordinator, and manager,
 * including direct calls and stale service reservations.
 */
internal object RetiredFolderVisibilityTransfer {
    const val ACTIVE = false
    const val MESSAGE = "Старый перенос скрытых папок отключён. Сохранённые данные доступны для диагностики."

    fun requireActive() {
        check(ACTIVE) { MESSAGE }
    }
}
