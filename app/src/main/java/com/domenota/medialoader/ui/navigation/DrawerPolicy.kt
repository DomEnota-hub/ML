package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.data.library.AppMode
import kotlin.math.abs

internal const val MUSIC_SEARCH_ROUTE="music-search"
internal enum class DrawerEntry(val label:String,val availableInStage3:Boolean=true){
    HOME("Главная"),DOWNLOADS("Загрузки"),GALLERY("Галерея"),MUSIC("Музыка"),
    MUSIC_SEARCH("Поиск музыки"),
    FAVORITES("Избранное",false),PRIVATE("Личное",false),ARCHIVE("Архив",false),
    COLLECTIONS("Коллекции",false),TRASH("Корзина",false),
    SETTINGS("Настройки"),APPEARANCE("Оформление"),JOURNAL("Журнал",false)
}
internal fun drawerRouteAllowed(route:String?,mode:AppMode):Boolean =
    route=="downloads" || (route=="home" && mode in setOf(AppMode.DOWNLOADER,AppMode.GALLERY,AppMode.MUSIC))

internal fun drawerAllowed(route:String?,mode:AppMode,screenAdmitted:Boolean,blocked:Boolean):Boolean =
    drawerRouteAllowed(route,mode)&&screenAdmitted&&!blocked

internal fun drawerActiveEntry(route:String?,mode:AppMode):DrawerEntry? = when(route){
    "downloads"->DrawerEntry.DOWNLOADS
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
