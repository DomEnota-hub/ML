package com.domenota.medialoader.data.editor

import kotlin.math.abs

/** Conservative white-fill / dark-outline verification, with shared outline
 * components resolved across the selected glyphs of a credible line. Boxes
 * limit search only; every selected pixel needs actual style evidence. */
internal object OutlinedGlyphInk {
    data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int, val pad: Int) {
        val width get() = right-left
        val height get() = bottom-top
        val area get() = (width+pad*2).toLong()*(height+pad*2)
        val radius get() = (minOf(width,height)*.20f).toInt().coerceIn(2,6)
        fun contains(x:Int,y:Int) = x in left until right && y in top until bottom
    }
    data class Result(val owner: IntArray, val verified: Set<Int>)
    private data class Core(val owner:Int,val pixels:IntArray,val bridges:IntArray,val dark:Set<Int>)

    fun extract(pixels: IntArray, width: Int, height: Int): BooleanArray? {
        val result = extractRegions(pixels,width,height,listOf(Region(0,0,width,height,0))) ?: return null
        if (0 !in result.verified) return null
        return BooleanArray(result.owner.size) { result.owner[it] == 0 }
    }

    /** Resolve partial groups without turning a partial glyph into a success.
     * A connected dark outline can be shared by adjacent selected letters, but
     * never extends to a seam/frame, an unselected glyph or unrelated texture. */
    fun extractRegions(pixels:IntArray,width:Int,height:Int,regions:List<Region>):Result? {
        if (width<7 || height<7 || width.toLong()*height>600_000 || pixels.size!=width*height ||
            regions.isEmpty() || regions.size>160 || regions.any { it.width<=0 || it.height<=0 ||
                it.left<0 || it.top<0 || it.right>width || it.bottom>height || it.pad !in 0..8 }) return null
        fun shade(p:Int):Int {
            val r=p ushr 16 and 255;val g=p ushr 8 and 255;val b=p and 255
            if (p ushr 24<220 || maxOf(r,g,b)-minOf(r,g,b)>35) return -1
            return (r+g+b)/3
        }
        val shades=IntArray(pixels.size){shade(pixels[it])}
        val allowed=IntArray(pixels.size){-1};val allowedDistance=IntArray(pixels.size){Int.MAX_VALUE}
        for ((id,r) in regions.withIndex()) {
            for(y in (r.top-r.pad).coerceAtLeast(0) until (r.bottom+r.pad).coerceAtMost(height))
                for(x in (r.left-r.pad).coerceAtLeast(0) until (r.right+r.pad).coerceAtMost(width)) {
                    val index=y*width+x
                    val d=maxOf(0,r.left-x,x-r.right+1)+maxOf(0,r.top-y,y-r.bottom+1)
                    if(d<allowedDistance[index]){allowedDistance[index]=d;allowed[index]=id}
                }
        }
        val queue=IntArray(pixels.size)
        fun neighbors(i:Int,action:(Int)->Unit) {
            val x=i%width;val y=i/width
            if(x>0)action(i-1);if(x+1<width)action(i+1)
            if(y>0)action(i-width);if(y+1<height)action(i+width)
        }
        fun touchesEdge(i:Int)=i%width==0 || i%width==width-1 || i/width==0 || i/width==height-1
        val darkAt=IntArray(pixels.size){-1};val darkComponents=ArrayList<IntArray>()
        for(start in pixels.indices) {
            if(darkAt[start]>=0 || shades[start] !in 0..80)continue
            if(darkComponents.size>=1024)return null
            val id=darkComponents.size;var head=0;var tail=1;queue[0]=start;darkAt[start]=id
            while(head<tail)neighbors(queue[head++]){n->
                if(darkAt[n]<0 && shades[n] in 0..80){darkAt[n]=id;queue[tail++]=n}
            }
            darkComponents.add(queue.copyOf(tail))
        }
        val labels=BooleanArray(pixels.size);val bad=BooleanArray(regions.size)
        val cores=ArrayList<Core>();var substantial=0
        for(start in pixels.indices) {
            if(labels[start] || shades[start]<210)continue
            var head=0;var tail=1;queue[0]=start;labels[start]=true
            var edge=false;var boundary=0;var darkBoundary=0
            val adjacent=mutableMapOf<Int,Int>();val bridges=mutableSetOf<Int>()
            while(head<tail) {
                val i=queue[head++];if(touchesEdge(i))edge=true
                neighbors(i){n->
                    if(shades[n]>=210){if(!labels[n]){labels[n]=true;queue[tail++]=n}}
                    else {
                        boundary++
                        var dark=darkAt[n]
                        // A one-pixel neutral transition between white fill
                        // and real black ink is anti-aliasing, not empty space.
                        if(dark<0 && shades[n] in 81..209) {
                            val next=n+(n-i)
                            if(next in pixels.indices && abs(next%width-n%width)+abs(next/width-n/width)==1)
                                dark=darkAt[next]
                            if(dark<0)neighbors(n){nn->if(dark<0 && darkAt[nn]>=0)dark=darkAt[nn]}
                            if(dark>=0)bridges.add(n)
                        }
                        if(dark>=0){darkBoundary++;adjacent[dark]=(adjacent[dark]?:0)+1}
                    }
                }
            }
            if(tail<5)continue
            if(++substantial>1024)return null
            val counts=IntArray(regions.size)
            for(j in 0 until tail){val id=allowed[queue[j]];if(id>=0)counts[id]++}
            val owner=counts.indices.maxByOrNull{counts[it]}?:continue
            if(counts[owner]==0)continue
            val region=regions[owner]
            val inside=(0 until tail).count{j->val i=queue[j];region.contains(i%width,i/width)}
            // Large bright backgrounds that reach the crop boundary are not
            // glyphs. A clipped candidate predominately in one selected box
            // is ambiguous, not a complete symbol.
            if(edge){if(inside*100L>=tail*65L && tail<=region.area)bad[owner]=true;continue}
            if(counts[owner]*100L<tail*75L || inside*100L<tail*50L) {
                if(inside>=5)bad[owner]=true
                continue
            }
            val darkIds=adjacent.filterValues{it>=2}.keys
            if(tail*100L>region.area*65L || boundary<8 || darkBoundary*100L<boundary*80L ||
                darkIds.isEmpty() || darkIds.sumOf{adjacent.getValue(it)}*100L<boundary*80L ||
                bridges.any{allowed[it]<0}) {
                bad[owner]=true;continue
            }
            cores.add(Core(owner,queue.copyOf(tail),bridges.toIntArray(),darkIds.toSet()))
        }
        if(cores.isEmpty())return null
        val darkOwners=Array(darkComponents.size){mutableSetOf<Int>()}
        val coreCounts=IntArray(regions.size)
        val nearest=IntArray(pixels.size){-1};val distance=IntArray(pixels.size){Int.MAX_VALUE}
        var head=0;var tail=0
        for(core in cores) {
            coreCounts[core.owner]++
            for(id in core.dark)darkOwners[id].add(core.owner)
            for(i in core.pixels+core.bridges)if(distance[i]!=0){
                distance[i]=0;nearest[i]=core.owner;queue[tail++]=i
            }
        }
        // Multi-source, bounded distance, not a flood of background pixels.
        // This distance is a test for the already observed dark components;
        // it does not itself add any pixel to the mask.
        while(head<tail) {
            val i=queue[head++];val d=distance[i]+1
            if(d>6)continue
            neighbors(i){n->if(distance[n]==Int.MAX_VALUE){distance[n]=d;nearest[n]=nearest[i];queue[tail++]=n}}
        }
        for(id in darkComponents.indices) {
            val owners=darkOwners[id];if(owners.isEmpty())continue
            val outline=darkComponents[id]
            val area=owners.sumOf{regions[it].area}
            if(outline.size*100L>area*55L || outline.any{i->
                    val owner=nearest[i]
                    touchesEdge(i) || allowed[i]<0 || owner !in owners || distance[i]>regions[owner].radius
                }) owners.forEach{bad[it]=true}
        }
        for(i in regions.indices)if(coreCounts[i]==0)bad[i]=true
        fun propagateBad(){
            var changed:Boolean
            do {changed=false;for(owners in darkOwners)if(owners.any{bad[it]})for(id in owners)if(!bad[id]){bad[id]=true;changed=true}}while(changed)
        }
        propagateBad()
        fun assemble():IntArray {
            val output=IntArray(pixels.size){-1}
            for(core in cores)if(!bad[core.owner])for(i in core.pixels+core.bridges)output[i]=core.owner
            for(id in darkComponents.indices)if(darkOwners[id].isNotEmpty() && darkOwners[id].none{bad[it]})
                for(i in darkComponents[id])output[i]=nearest[i]
            return output
        }
        var output=assemble();val counts=IntArray(regions.size)
        for(id in output)if(id>=0)counts[id]++
        for(i in regions.indices)if(counts[i]<10 || counts[i]*100L>regions[i].area*80L)bad[i]=true
        propagateBad();output=assemble()
        val verified=regions.indices.filterNot{bad[it]}.toSet()
        return if(verified.isEmpty())null else Result(output,verified)
    }
}
