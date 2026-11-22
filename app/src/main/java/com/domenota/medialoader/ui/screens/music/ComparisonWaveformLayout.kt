package com.domenota.medialoader.ui.screens.music

/** A focus window always covers the entire selected change, bounded by existing columns. */
internal fun comparisonFocusRange(count:Int,start:Int,endExclusive:Int,close:Boolean):IntRange {
    if(count<=0)return IntRange.EMPTY
    val a=start.coerceIn(0,count-1)
    val b=endExclusive.coerceIn(a+1,count)
    val pad=if(close)2 else maxOf(8,(b-a)/2)
    return maxOf(0,a-pad) until minOf(count,b+pad)
}

/** Every frame exactly once; preserves narrow transients in compressed waveforms. */
internal fun comparisonBucket(start:Int,count:Int,bins:Int,index:Int):IntRange {
    require(count>0&&bins in 1..count&&index in 0 until bins)
    return (start+(index.toLong()*count/bins).toInt()) until
        (start+((index+1L)*count/bins).toInt())
}
