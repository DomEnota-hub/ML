package com.domenota.medialoader.core.model

/** Shared Russian count labels, including 11–14 regardless of the last digit. */
object RussianCounts {
    private fun count(n:Int,one:String,few:String,many:String):String {
        require(n>=0)
        val noun=when {
            n%100 in 11..14 -> many
            n%10==1 -> one
            n%10 in 2..4 -> few
            else -> many
        }
        return "$n $noun"
    }
    fun files(n:Int)=count(n,"файл","файла","файлов")
    fun tracks(n:Int)=count(n,"трек","трека","треков")
    fun links(n:Int)=count(n,"ссылка","ссылки","ссылок")
}
