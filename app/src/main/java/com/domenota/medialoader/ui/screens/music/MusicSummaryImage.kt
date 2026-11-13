package com.domenota.medialoader.ui.screens.music

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import com.domenota.medialoader.data.playback.SmartMusicSnapshot

/** A local, shareable PNG. Receives only the current public statistics snapshot. */
internal data class MusicSummaryExport(val period:String,val snapshot:SmartMusicSnapshot,val topTitle:String?,
    val background:Int,val surface:Int,val foreground:Int,val secondary:Int,val accent:Int)

internal object MusicSummaryImage {
    const val WIDTH=1080
    const val HEIGHT=1440
    fun render(data:MusicSummaryExport):Bitmap {
        val bitmap=Bitmap.createBitmap(WIDTH,HEIGHT,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap)
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(data.background)
        fun text(value:String,x:Float,y:Float,size:Float,color:Int,bold:Boolean=false,width:Float=948f) {
            paint.color=color;paint.textSize=size
            paint.typeface=Typeface.create("sans-serif",if(bold) Typeface.BOLD else Typeface.NORMAL)
            canvas.drawText(TextUtils.ellipsize(value.replace('\n',' '),paint,width,TextUtils.TruncateAt.END).toString(),x,y,paint)
        }
        fun block(left:Float,top:Float,right:Float,bottom:Float){
            paint.color=data.surface;canvas.drawRoundRect(left,top,right,bottom,28f,28f,paint)
        }
        text("ML · МУЗЫКА",66f,100f,32f,data.accent,true)
        text("Твой музыкальный итог",66f,190f,60f,data.foreground,true)
        text(data.period,66f,246f,35f,data.secondary)
        val stats=data.snapshot
        fun metric(x:Float,y:Float,label:String,value:String){
            block(x,y,x+458f,y+176f)
            text(value,x+28,y+76,48f,data.accent,true,400f)
            text(label,x+28,y+132,28f,data.foreground,width=400f)
        }
        val seconds=stats.wallMs/1000
        val duration=if(seconds>=3600) "${seconds/3600} ч ${seconds/60%60} мин" else "${seconds/60} мин ${seconds%60} с"
        metric(66f,306f,"Время прослушивания",duration)
        metric(556f,306f,"Прослушивания",stats.plays.toString())
        metric(66f,508f,"Разные треки",stats.tracksPlayed.toString())
        metric(556f,508f,"Исполнители",stats.artistsPlayed.toString())
        block(66f,718f,1014f,898f)
        text("Трек периода",96f,780f,32f,data.secondary)
        text(data.topTitle?:"Пока нет данных",96f,850f,47f,data.foreground,true,888f)
        text("Любимые исполнители",66f,984f,39f,data.foreground,true)
        stats.topArtists.take(4).forEachIndexed { index,artist ->
            val y=1055f+index*64f
            text("${index+1}. ${artist.artist}",66f,y,34f,data.foreground,width=790f)
            text(artist.plays.toString(),872f,y,32f,data.accent,true,140f)
        }
        text("Локальная статистика MediaLoader",66f,1380f,28f,data.secondary)
        return bitmap
    }
}
