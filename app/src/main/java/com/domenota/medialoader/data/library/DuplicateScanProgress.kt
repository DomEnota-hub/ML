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
        return (if(music)when(phase){DuplicateScanPhase.METADATA->f*.35f;DuplicateScanPhase.ACOUSTIC->.35f+f*.55f;else->0f}else f*.9f).coerceIn(0f,.9f)
    }
    fun etaMs(done:Int,total:Int,elapsedMs:Long):Long?{
        if(done<5||total<=done||elapsedMs<5_000)return null
        return (elapsedMs.toDouble()/done*(total-done)).toLong().coerceAtLeast(0)
    }
}
