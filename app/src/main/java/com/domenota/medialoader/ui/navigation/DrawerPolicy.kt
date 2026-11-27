package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.data.library.AppMode
import kotlin.math.abs

internal const val MUSIC_SEARCH_ROUTE="music-search"
internal const val FAVORITES_ROUTE="favorites"
internal enum class DrawerEntry(val label:String){
    HOME("Главная"),DOWNLOADS("Загрузки"),GALLERY("Галерея"),MUSIC("Музыка"),
    MUSIC_SEARCH("Поиск музыки"),
    FAVORITES("Избранное"),PRIVATE("Личное"),ARCHIVE("Архив"),
    COLLECTIONS("Коллекции"),TRASH("Корзина"), TASK_CENTER("Центр задач"),
    SETTINGS("Настройки"),APPEARANCE("Оформление"),JOURNAL("Журнал"),
    VISUAL_DUPLICATES("Дубликаты фото и видео"), MUSIC_DUPLICATES("Дубликаты музыки"),
    EQUALIZER("Эквалайзер"), BULK_DOWNLOAD("Массовая загрузка"),
    DIAGNOSTICS("Диагностика"), VK_IMAGES("Изображения VK")
}
/** Primary modes have no alternate switcher. Keep their destinations reachable. */
internal val mandatoryDrawerEntries: Set<DrawerEntry> = setOf(
    DrawerEntry.HOME, DrawerEntry.GALLERY, DrawerEntry.MUSIC, DrawerEntry.SETTINGS, DrawerEntry.TASK_CENTER,
)

/** Diagnostics is hidden-only; Journal retains its persisted developer gate. */
internal fun drawerAvailableEntries(journalUnlocked:Boolean, advancedToolsVisible:Boolean=false):Set<DrawerEntry> =
    DrawerEntry.entries.filterTo(linkedSetOf()){
        it!=DrawerEntry.DIAGNOSTICS && (journalUnlocked || it!=DrawerEntry.JOURNAL) &&
            (advancedToolsVisible || (it!=DrawerEntry.BULK_DOWNLOAD && it!=DrawerEntry.VK_IMAGES))
    }

internal fun drawerRouteAllowed(route:String?,mode:AppMode):Boolean =
    when(route){
        "home","downloads","simple-archive",FAVORITES_ROUTE,"private","collections","music-duplicates","task-center"->true
        "settings"->true
        else->false
    }

internal fun drawerPrivateContextAllowed(route:String?,privateNavigation:Boolean,unlocked:Boolean):Boolean =
    if(route=="private") privateNavigation&&unlocked else !privateNavigation

internal fun drawerAllowed(route:String?,mode:AppMode,screenAdmitted:Boolean,blocked:Boolean):Boolean =
    drawerRouteAllowed(route,mode)&&screenAdmitted&&!blocked

internal fun drawerActiveEntry(route:String?,mode:AppMode):DrawerEntry? = when(route){
    "task-center"->DrawerEntry.TASK_CENTER
    "downloads"->DrawerEntry.DOWNLOADS
    "simple-archive"->DrawerEntry.ARCHIVE
    FAVORITES_ROUTE->DrawerEntry.FAVORITES
    "private"->DrawerEntry.PRIVATE
    "collections"->DrawerEntry.COLLECTIONS
    "music-duplicates"->DrawerEntry.MUSIC
    "settings"->DrawerEntry.SETTINGS
    "home"->when(mode){
        AppMode.DOWNLOADER->DrawerEntry.HOME
        AppMode.GALLERY->DrawerEntry.GALLERY
        AppMode.MUSIC->DrawerEntry.MUSIC
    }
    else->null
}

/** Pixel-space helpers: opening starts immediately inside app content, after
 * the left system-gesture inset. No system gesture-exclusion rectangles. */
internal object DrawerGesturePolicy {
    fun canStart(x:Float,progress:Float,panelWidth:Float,systemInset:Float,edge:Float):Boolean =
        panelWidth>0f && if(progress>0f) x>=0f&&x<=panelWidth*progress
        else x>=systemInset&&x<=systemInset+edge
    fun acceptHorizontal(dx:Float,dy:Float,slop:Float,opening:Boolean):Boolean =
        abs(dx)>slop&&abs(dx)>abs(dy)*1.25f&&(if(opening)dx>0f else dx<0f)
    fun rejectDirection(dx:Float,dy:Float,slop:Float,opening:Boolean):Boolean =
        (abs(dy)>slop&&abs(dy)>=abs(dx)/1.25f)||
            (abs(dx)>slop&&if(opening)dx<0f else dx>0f)
    fun fraction(start:Float,dx:Float,width:Float):Float =
        if(width>0f)(start+dx/width).coerceIn(0f,1f)else 0f
    fun settleOpen(progress:Float,opening:Boolean):Boolean =
        if(opening)progress>=.35f else progress>=.65f
}
