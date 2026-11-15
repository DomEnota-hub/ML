package com.domenota.medialoader.ui.screens.organization

import kotlin.math.min

internal data class CollectionCropRect(val left:Float,val top:Float,val width:Float,val height:Float)
/** Center is normalized in the EXIF-oriented source, zoom is relative to fill. */
internal data class CollectionCrop(val centerX:Float=.5f,val centerY:Float=.5f,val zoom:Float=1f) {
    fun bounded(width:Int,height:Int):CollectionCrop {
        require(width>0 && height>0)
        val z=zoom.takeIf{it.isFinite()}?.coerceIn(1f,8f)?:1f
        val baseWidth=min(width.toDouble(),height*.8)/z
        val baseHeight=baseWidth/.8
        fun center(value:Float, fraction:Double):Float {
            val half=(fraction.coerceIn(0.0,1.0)/2).toFloat()
            return (value.takeIf{it.isFinite()}?:.5f).coerceIn(half,1f-half)
        }
        return CollectionCrop(center(centerX,baseWidth/width),center(centerY,baseHeight/height),z)
    }
    fun rect(width:Int,height:Int):CollectionCropRect {
        val c=bounded(width,height)
        val w=(min(width.toDouble(),height*.8)/c.zoom).toFloat().coerceAtMost(width.toFloat())
        val h=(w.toDouble()/.8).toFloat().coerceAtMost(height.toFloat())
        return CollectionCropRect((c.centerX*width-w/2).coerceIn(0f,(width-w).coerceAtLeast(0f)),(c.centerY*height-h/2).coerceIn(0f,(height-h).coerceAtLeast(0f)),w,h)
    }
}
internal fun collectionCardWidth(available:Float)=((available-8f)/2).coerceAtLeast(1f)
internal fun collectionRangeSelection(previous:Set<String>,visible:List<String>,anchor:String,current:String,existing:Set<String>):Set<String> {
    val start=visible.indexOf(anchor);val end=visible.indexOf(current)
    if(start<0||end<0)return previous
    return previous+visible.subList(minOf(start,end),maxOf(start,end)+1).filterNot(existing::contains)
}
