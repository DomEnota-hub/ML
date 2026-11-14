package com.domenota.medialoader.ui.screens.organization

import kotlin.math.min

internal data class CollectionCropRect(val left:Float,val top:Float,val width:Float,val height:Float)
/** Center is normalized in the EXIF-oriented source, zoom is relative to fill. */
internal data class CollectionCrop(val centerX:Float=.5f,val centerY:Float=.5f,val zoom:Float=1f) {
    fun bounded(width:Int,height:Int):CollectionCrop {
        require(width>0 && height>0)
        val z=zoom.takeIf{it.isFinite()}?.coerceIn(1f,8f)?:1f
        val baseWidth=min(width.toFloat(),height*.8f)/z
        val baseHeight=baseWidth/.8f
        return CollectionCrop(centerX.takeIf{it.isFinite()}?.coerceIn(baseWidth/width/2,1-baseWidth/width/2)?:.5f,
            centerY.takeIf{it.isFinite()}?.coerceIn(baseHeight/height/2,1-baseHeight/height/2)?:.5f,z)
    }
    fun rect(width:Int,height:Int):CollectionCropRect {
        val c=bounded(width,height)
        val w=min(width.toFloat(),height*.8f)/c.zoom
        val h=w/.8f
        return CollectionCropRect((c.centerX*width-w/2).coerceAtLeast(0f),(c.centerY*height-h/2).coerceAtLeast(0f),w,h)
    }
}
internal fun collectionCardWidth(available:Float)=((available-8f)/2).coerceAtLeast(1f)
internal fun collectionRangeSelection(previous:Set<String>,visible:List<String>,anchor:String,current:String,existing:Set<String>):Set<String> {
    val start=visible.indexOf(anchor);val end=visible.indexOf(current)
    if(start<0||end<0)return previous
    return previous+visible.subList(minOf(start,end),maxOf(start,end)+1).filterNot(existing::contains)
}
