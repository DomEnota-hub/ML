package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.model.MediaType
import kotlin.math.abs

/** Similarity is a review suggestion, never evidence that deletion is safe. */
internal data class VisualFingerprint(val id:String,val uri:String,val type:MediaType,val bytes:Long,
    val modified:Long?,val sha:String,val width:Int,val height:Int,val durationMs:Long?,
    val frames:List<Long>,val informative:Boolean)
internal enum class VisualMatch { EXACT, SIMILAR_PHOTO, SIMILAR_VIDEO }
internal data class VisualDuplicateGroup(val files:List<VisualFingerprint>,val reason:VisualMatch)
internal object VisualDuplicatePolicy {
    fun match(a:VisualFingerprint,b:VisualFingerprint):VisualMatch? {
        if(a.id==b.id||a.uri==b.uri||a.type!=b.type)return null
        if(a.bytes>0&&a.bytes==b.bytes&&a.sha.isNotBlank()&&a.sha==b.sha)return VisualMatch.EXACT
        if(!a.informative||!b.informative||a.width<=0||a.height<=0||b.width<=0||b.height<=0)return null
        val ratioA=a.width.toDouble()/a.height;val ratioB=b.width.toDouble()/b.height
        if(abs(ratioA/ratioB-1.0)>0.08)return null
        if(a.frames.size!=b.frames.size||a.frames.isEmpty())return null
        val distances=a.frames.zip(b.frames).map{(x,y)->java.lang.Long.bitCount(x xor y)}
        return when(a.type){
            MediaType.PHOTO->if(distances.size==1&&distances.single()<=5)VisualMatch.SIMILAR_PHOTO else null
            MediaType.VIDEO->{
                val x=a.durationMs?:return null;val y=b.durationMs?:return null
                if(x<=0||y<=0||abs(x-y)>maxOf(500L,maxOf(x,y)/50)||distances.size!=5)return null
                if(distances.all{it<=7}&&distances.average()<=5)VisualMatch.SIMILAR_VIDEO else null
            }
            else->null
        }
    }
    /** Complete-link groups: no A~B~C chaining when A and C disagree.
     * Exact groups are separate from approximate candidates. */
    fun groups(input:List<VisualFingerprint>,cancelCheck:()->Unit={}):List<VisualDuplicateGroup>{
        val rows=input.filter{it.type!=MediaType.AUDIO}.distinctBy{it.uri}.distinctBy{it.id}.sortedBy{it.id}
        val used=mutableSetOf<String>();val output=mutableListOf<VisualDuplicateGroup>()
        rows.filter{it.sha.isNotBlank()&&it.bytes>0}.groupBy{Triple(it.type,it.bytes,it.sha)}.values.forEach{same->
            cancelCheck()
            if(same.size>1){output+=VisualDuplicateGroup(same,VisualMatch.EXACT);used+=same.map{it.id}}
        }
        // Index first frame in eight disjoint byte bands. Any <=7-bit
        // Hamming match shares at least one band; avoids full all-pairs scan.
        val remaining=rows.filter{it.id !in used&&it.informative&&it.frames.isNotEmpty()}
        fun bands(f:VisualFingerprint)= (0..7).map{Triple(f.type,it,(f.frames.first() ushr (it*8)) and 255L)}
        val index=remaining.flatMap{f->bands(f).map{it to f}}.groupBy({it.first},{it.second})
        for(seed in remaining){
            cancelCheck()
            if(seed.id in used)continue
            val group=mutableListOf(seed)
            val candidates=bands(seed).flatMap{index[it].orEmpty()}.distinctBy{it.id}.sortedBy{it.id}
            for(candidate in candidates){
                cancelCheck()
                if(candidate.id==seed.id||candidate.id in used)continue
                if(group.all{match(it,candidate)!=null})group+=candidate
            }
            if(group.size>1){
                output+=VisualDuplicateGroup(group,if(seed.type==MediaType.PHOTO)VisualMatch.SIMILAR_PHOTO else VisualMatch.SIMILAR_VIDEO)
                used+=group.map{it.id}
            }
        }
        return output
    }
}
