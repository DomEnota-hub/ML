package com.domenota.medialoader.data.library

internal enum class DuplicateScanPhase(val caption:String){
    METADATA("Метаданные и точные совпадения"),
    ACOUSTIC("Сравнение звучания"),
    VISUAL("Отпечатки фото и видео"),
    GROUPING("Формирование групп"),
    SAVING("Сохранение результата")
}
internal object DuplicateProgressPolicy {
    fun fraction(phase:DuplicateScanPhase,done:Int,total:Int,music:Boolean):Float?{
        if(phase==DuplicateScanPhase.GROUPING||phase==DuplicateScanPhase.SAVING||total<=0)return null
        val f=done.coerceIn(0,total).toFloat()/total
        // This is progress of the current measured phase, not a fabricated total percentage.
        return f
    }
    fun etaMs(done:Int,total:Int,elapsedMs:Long):Long?{
        if(done<5||total<=done||elapsedMs<5_000)return null
        return (elapsedMs.toDouble()/done*(total-done)).toLong().coerceAtLeast(0)
    }
}
