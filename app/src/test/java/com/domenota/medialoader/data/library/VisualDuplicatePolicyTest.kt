package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class VisualDuplicatePolicyTest {
    private fun f(id:String,hash:Long=0,sha:String=id,type:MediaType=MediaType.PHOTO,
        duration:Long?=null,informative:Boolean=true)=VisualFingerprint(id,"content://media/$id",type,100,1,sha,1920,1080,duration,
        if(type==MediaType.VIDEO)List(5){hash} else listOf(hash),informative)
    @Test fun exactRequiresBytesAndHash(){
        assertEquals(VisualMatch.EXACT,VisualDuplicatePolicy.match(f("a",sha="same"),f("b",sha="same")))
        assertNull(VisualDuplicatePolicy.match(f("a",sha="same"),f("b",sha="same").copy(bytes=101,informative=false)))
    }
    @Test fun similarPhotoDoesNotChain(){
        val a=f("a",0);val b=f("b",31);val c=f("c",1023)
        assertEquals(VisualMatch.SIMILAR_PHOTO,VisualDuplicatePolicy.match(a,b))
        assertNull(VisualDuplicatePolicy.match(a,c))
        assertEquals(2,VisualDuplicatePolicy.groups(listOf(a,b,c)).single().files.size)
    }
    @Test fun videoRequiresDurationAndAllFrames(){
        val a=f("a",type=MediaType.VIDEO,duration=10000)
        val b=f("b",type=MediaType.VIDEO,duration=10200)
        assertEquals(VisualMatch.SIMILAR_VIDEO,VisualDuplicatePolicy.match(a,b))
        assertNull(VisualDuplicatePolicy.match(a,b.copy(durationMs=12000)))
        assertNull(VisualDuplicatePolicy.match(a,b.copy(frames=listOf(0,0,0,0,-1L))))
    }
    @Test fun excludesBlankAndSameLocator(){
        val a=f("a")
        assertNull(VisualDuplicatePolicy.match(a,f("b",informative=false)))
        assertNull(VisualDuplicatePolicy.match(a,f("b").copy(uri=a.uri)))
        assertTrue(VisualDuplicatePolicy.groups(listOf(a,a.copy(id="b"))).isEmpty())
    }
}
