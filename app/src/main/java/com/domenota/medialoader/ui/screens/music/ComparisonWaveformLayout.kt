package com.domenota.medialoader.ui.screens.music

/** A focus window always covers the entire selected change, bounded by existing columns. */
internal fun comparisonFocusRange(count:Int,start:Int,endExclusive:Int,close:Boolean):IntRange =
    comparisonFocusRange(count,start,endExclusive,if(close)3 else 1)

/** Every frame exactly once; preserves narrow transients in compressed waveforms. */
internal fun comparisonBucket(start:Int,count:Int,bins:Int,index:Int):IntRange {
    require(count>0&&bins in 1..count&&index in 0 until bins)
    return (start+(index.toLong()*count/bins).toInt()) until
        (start+((index+1L)*count/bins).toInt())
}

/** Build playback frame-to-column cursors once per aligned pair instead of at every tick. */
internal fun comparisonCursorColumns(columns:List<com.domenota.medialoader.data.library.ComparisonColumn>,
    firstCount:Int,secondCount:Int):Pair<IntArray,IntArray>{
    val first=IntArray(firstCount){-1};val second=IntArray(secondCount){-1}
    columns.forEachIndexed{index,column->
        column.first?.takeIf{it in first.indices}?.let{if(first[it]<0)first[it]=index}
        column.second?.takeIf{it in second.indices}?.let{if(second[it]<0)second[it]=index}
    }
    return first to second
}

/** Seek the active recording, snapping gaps to its closest genuine audio frame. */
internal fun comparisonSeekFrame(columns:List<com.domenota.medialoader.data.library.ComparisonColumn>,
    column:Int,firstTrack:Boolean):Int?{
    if(column !in columns.indices)return null
    fun frame(index:Int)=if(firstTrack)columns[index].first else columns[index].second
    frame(column)?.let{return it}
    for(distance in 1 until columns.size){
        val left=column-distance
        if(left>=0)frame(left)?.let{return it}
        val right=column+distance
        if(right<columns.size)frame(right)?.let{return it}
    }
    return null
}

/** Peak-preserving bins cached across redraws, invalidated on range/width changes. */
internal class ComparisonPeakCache(private val values:List<Pair<Float,Float>>,private val range:IntRange){
    private var lastBins=-1
    private var last:Pair<FloatArray,FloatArray>?=null
    fun peaks(bins:Int):Pair<FloatArray,FloatArray>{
        require(!range.isEmpty()&&range.first>=0&&range.last<values.size&&bins in 1..range.count())
        last?.takeIf{lastBins==bins}?.let{return it}
        val first=FloatArray(bins);val second=FloatArray(bins)
        for(bin in 0 until bins){
            for(column in comparisonBucket(range.first,range.count(),bins,bin)){
                first[bin]=maxOf(first[bin],values[column].first)
                second[bin]=maxOf(second[bin],values[column].second)
            }
        }
        return (first to second).also{last=it;lastBins=bins}
    }
}

/** Four bounded focus levels always containing the full changed region. */
internal fun comparisonFocusRange(count:Int,start:Int,endExclusive:Int,zoomLevel:Int):IntRange{
    if(count<=0)return IntRange.EMPTY
    val a=start.coerceIn(0,count-1)
    val b=endExclusive.coerceIn(a+1,count)
    val pad=when(zoomLevel.coerceIn(1,4)){
        1->maxOf(8,(b-a)/2)
        2->maxOf(4,(b-a)/4)
        3->2
        else->0
    }
    return maxOf(0,a-pad) until minOf(count,b+pad)
}
