package com.domenota.medialoader.ui.screens.gallery

/** Keep scan admission strict and expose the same reason in the UI and log. */
internal object VisualScanStartGate {
    enum class Blocker(val code:String,val message:String) {
        PERMISSION("permission","Нет доступа к фото и видео"),
        RUNNING("running","Поиск уже выполняется"),
        RESTORE("checkpoint_restore","Восстанавливаю сохранённый поиск"),
        WRITE("checkpoint_write","Сохраняю результаты"),
        TRASH("trash","Выполняется перемещение в корзину"),
        CATALOG("catalog_not_ready","Проверяю актуальность каталога"),
        EMPTY("empty_catalog","Нет доступных файлов")
    }
    fun blocker(ready:Boolean, permission:Boolean, loaded:Boolean, saving:Boolean,
        trash:Boolean, hasFiles:Boolean, generation:Boolean, running:Boolean): Blocker? = when {
        !permission -> Blocker.PERMISSION
        running -> Blocker.RUNNING
        !loaded -> Blocker.RESTORE
        saving -> Blocker.WRITE
        trash -> Blocker.TRASH
        !ready || !generation -> Blocker.CATALOG
        !hasFiles -> Blocker.EMPTY
        else -> null
    }
}
