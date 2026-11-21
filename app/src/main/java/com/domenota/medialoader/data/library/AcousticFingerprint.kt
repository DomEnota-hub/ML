package com.domenota.medialoader.data.library

import kotlin.math.*

/** Original spectral landmark implementation. No PCM hashes, network or native dependency.
 * Mono 8 kHz; 1024 point Hann FFT / 256 hop. Time differences survive leading trims.
 * Fingerprints indicate matching fragments, never that two whole recordings are interchangeable.
 */
internal data class AcousticFingerprint(val landmarks: List<AcousticLandmark>) {
    companion object { const val VERSION = 2 }
}
internal data class AcousticLandmark(val hash: Int, val frame: Int)
internal data class AcousticAlignment(val firstMs:Long,val secondMs:Long)
/** Keep large libraries in primitive storage; list equality and the serialized format are unchanged. */
internal fun packedAcousticLandmarks(values:List<AcousticLandmark>):List<AcousticLandmark>{
    val hashes=IntArray(values.size){values[it].hash};val frames=IntArray(values.size){values[it].frame}
    return object:AbstractList<AcousticLandmark>(){
        override val size:Int get()=hashes.size
        override fun get(index:Int)=AcousticLandmark(hashes[index],frames[index])
    }
}
internal object AcousticFingerprints {
    const val RATE = 8000
    private const val N = 1024
    private const val HOP = 256
    private const val LIMIT = 6000
    // Identical FFT coefficients, calculated once rather than for every frame.
    private val window = DoubleArray(N) { 0.5-0.5*cos(2*PI*it/(N-1)) }
    private val twiddleCos = Array(11) { stage -> val length=2 shl stage; DoubleArray(length/2) { cos(-2*PI/length*it) } }
    private val twiddleSin = Array(11) { stage -> val length=2 shl stage; DoubleArray(length/2) { sin(-2*PI/length*it) } }
    private val bands = arrayOf(8..24,25..56,57..112,113..224,225..480)
    class Builder(private val checkActive: () -> Unit = {}) {
        private val samples = FloatArray(N)
        private val re = DoubleArray(N)
        private val im = DoubleArray(N)
        private val power = DoubleArray(N/2)
        private var filled = 0
        private var frame = 0
        private val anchors = ArrayDeque<Pair<Int,IntArray>>()
        // Deterministic reservoir ranks by content/time; bounds retained memory without truncating endings.
        private val reservoir = java.util.PriorityQueue<Pair<Long,AcousticLandmark>>(compareByDescending { it.first })
        fun beginWindow(startMs:Long){
            filled=0;anchors.clear();frame=(startMs.coerceAtLeast(0)*RATE/1000/HOP).coerceAtMost(Int.MAX_VALUE.toLong()-N).toInt()
        }
        fun accept(value: Float) {
            samples[filled++] = value
            if (filled != N) return
            checkActive()
            // Only frames at delta 4/8 from an anchor can contribute landmarks.
            if(frame%4==0){
            val peaks = peaks(samples,re,im,power)
            for ((time, previous) in anchors) {
                val delta = frame-time
                if (delta == 4 || delta == 8) for (a in previous) for (b in peaks) {
                    val mark = AcousticLandmark((a shl 15) or (b shl 5) or delta, time)
                    var rank = (mark.hash.toLong() shl 32) xor time.toLong()
                    rank = (rank xor (rank ushr 33)) * -49064778989728563L
                    rank = (rank xor (rank ushr 33)) * -4265267296055464877L
                    rank = (rank xor (rank ushr 33)) and Long.MAX_VALUE
                    if (reservoir.size < LIMIT) reservoir.add(rank to mark)
                    else if (rank < reservoir.peek().first) { reservoir.poll(); reservoir.add(rank to mark) }
                }
            }
            if (frame % 8 == 0) anchors.addLast(frame to peaks)
            while (anchors.isNotEmpty() && frame-anchors.first().first >= 8) anchors.removeFirst()
            }
            frame++
            samples.copyInto(samples,0,HOP,N); filled = N-HOP
        }
        fun finish() = AcousticFingerprint(packedAcousticLandmarks(reservoir.map { it.second }.sortedBy { it.frame }))
    }
    private fun spectrum(samples: FloatArray,re:DoubleArray,im:DoubleArray,power:DoubleArray) {
        for(i in 0 until N) re[i]=samples[i]*window[i]
        im.fill(0.0)
        var j = 0
        for (i in 1 until N) { var bit = N shr 1; while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }; j = j xor bit
            if (i < j) { val v = re[i]; re[i] = re[j]; re[j] = v }
        }
        var length = 2
        var stage = 0
        while (length <= N) {
            val cosines=twiddleCos[stage]; val sines=twiddleSin[stage]
            for (start in 0 until N step length) for (k in 0 until length/2) {
                val c = cosines[k]; val s = sines[k]; val a = start+k; val b = a+length/2
                val tr = re[b]*c-im[b]*s; val ti = re[b]*s+im[b]*c
                re[b] = re[a]-tr; im[b] = im[a]-ti; re[a] += tr; im[a] += ti
            }; length *= 2; stage++
        }
        for(i in power.indices) power[i]=re[i]*re[i]+im[i]*im[i]
    }
    /** Complete pair analysis; no stored PCM, separate from candidate search. */
    class ComparisonBuilder(private val stepMs:Long,private val checkActive:()->Unit) {
        private val blocks = (stepMs * RATE / 1000 / N).toInt()
        private val samples = FloatArray(N)
        private val re = DoubleArray(N)
        private val im = DoubleArray(N)
        private val power = DoubleArray(N/2)
        private val bands = DoubleArray(64)
        private val frames = ArrayList<ComparisonFrame>()
        private var filled = 0
        private var count = 0
        private var energy = 0.0
        fun accept(value:Float) {
            samples[filled++] = value
            energy += value.toDouble() * value
            if(filled == N) {
                accumulate()
                if(count == blocks) emit()
            }
        }
        private fun accumulate() {
            checkActive()
            for(i in filled until N) samples[i] = 0f
            spectrum(samples,re,im,power)
            // Linear spectral bands, independent of absolute amplitude.
            for(i in 1 until power.size) bands[i/8] += power[i]
            count++
            filled = 0
        }
        private fun emit() {
            check(frames.size < 1800) { "Comparison duration exceeds limit" }
            frames.add(ComparisonFrame(AudioPairAlignment.normalizedSpectrum(bands),
                sqrt(energy / (count*N)).toFloat()))
            bands.fill(0.0); count=0; energy=0.0
        }
        fun finish():List<ComparisonFrame> {
            if(filled>0) accumulate()
            if(count>0) emit()
            return frames.toList()
        }
    }
    private fun peaks(samples: FloatArray,re:DoubleArray,im:DoubleArray,power:DoubleArray): IntArray {
        spectrum(samples,re,im,power)
        if (power.maxOrNull()!! < 1e-9) return intArrayOf()
        // One strongest local peak per broad band, exact bin landmark (not amplitude).
        val result=IntArray(bands.size); var count=0
        for(band in bands){
            var best=-1
            for(i in band) if(power[i]>power[i-1] && power[i]>=power[i+1] && (best<0 || power[i]>power[best])) best=i
            if(best>=0 && power[best]>1e-8) result[count++]=best
        }
        return result.copyOf(count)
    }
    /** Offset voting; repeated stationary tones cannot establish enough distinct evidence. */
    fun matches(a: AcousticFingerprint, b: AcousticFingerprint, checkActive: () -> Unit = {}): Boolean {
        val byHash = b.landmarks.groupBy { it.hash }
        val votes = HashMap<Int,MutableSet<Int>>()
        val spans = HashMap<Int,Pair<Int,Int>>()
        for (mark in a.landmarks) {
            checkActive()
            for (other in byHash[mark.hash].orEmpty().take(12)) {
                val offset = other.frame-mark.frame
                for (bucket in (offset-1)..(offset+1)) {
                    votes.getOrPut(bucket) { HashSet() }.add(mark.hash)
                    val span = spans[bucket]
                    spans[bucket] = minOf(span?.first ?: mark.frame,mark.frame) to maxOf(span?.second ?: mark.frame,mark.frame)
                }
            }
        }
        return votes.any { (offset, hashes) -> hashes.size >= 90 &&
            spans.getValue(offset).let { (lo,hi) -> (hi-lo)*HOP >= RATE*6 } }
    }
    /** Only expose a seek target when a strong, unambiguous offset is supported. */
    fun alignment(a:AcousticFingerprint,b:AcousticFingerprint):AcousticAlignment?{
        val byHash=b.landmarks.groupBy{it.hash}
        val votes=HashMap<Int,MutableSet<Int>>();val spans=HashMap<Int,Pair<Int,Int>>()
        for(mark in a.landmarks)for(other in byHash[mark.hash].orEmpty().take(12)){
            val offset=other.frame-mark.frame
            votes.getOrPut(offset){HashSet()}.add(mark.hash)
            val old=spans[offset];spans[offset]=minOf(old?.first?:mark.frame,mark.frame) to maxOf(old?.second?:mark.frame,mark.frame)
        }
        val best=votes.maxByOrNull{it.value.size}?:return null
        val span=spans.getValue(best.key)
        if(best.value.size<90||(span.second-span.first).toLong()*HOP<RATE*6)return null
        val competitor=votes.filterKeys{k->kotlin.math.abs(k-best.key)>2}.values.maxOfOrNull{it.size}?:0
        if(competitor*5>=best.value.size*4)return null
        // A seek position must be supported by one continuous region, not
        // just by unrelated landmarks in widely separated sample windows.
        val aligned=a.landmarks.filter{mark->byHash[mark.hash].orEmpty().take(12).any{it.frame-mark.frame==best.key}}.sortedBy{it.frame}
        val local=mutableSetOf<Int>();var start=-1;var last=-1
        for(mark in aligned){
            if(last<0||mark.frame-last>40){start=mark.frame;local.clear()}
            local.add(mark.hash);last=mark.frame
            if(local.size>=90&&(last-start).toLong()*HOP>=RATE*6){
                val second=start.toLong()+best.key
                if(second>=0)return AcousticAlignment(start.toLong()*HOP*1000/RATE,second*HOP*1000/RATE)
            }
        }
        return null
    }
    /** Capped primitive postings: same >=90 distinct-hash admission rule, no all-pairs scan. */
    private class Posting {
        private var values=IntArray(2)
        var size=0;private set
        private var last=-1
        fun add(track:Int){
            if(track==last||size>=41)return
            if(size==values.size)values=values.copyOf(minOf(41,values.size*2))
            values[size++]=track;last=track
        }
        operator fun get(index:Int)=values[index]
    }
    fun candidatePairs(input: Map<String,AcousticFingerprint>, checkActive: () -> Unit = {}): Set<Pair<String,String>> {
        val ids=input.keys.sorted()
        val index=HashMap<Int,Posting>()
        ids.forEachIndexed{track,id->
            checkActive()
            input.getValue(id).landmarks.forEach{mark->index.getOrPut(mark.hash){Posting()}.add(track)}
        }
        // A global HashMap<Long,Int> used one boxed entry for every candidate
        // pair, including pairs with too little evidence. In a 3k-track library
        // that could exhaust a 256-MiB heap during the final grouping stage.
        // Count one bounded tile of source tracks at a time. Voting saturates
        // at the unchanged 90 distinct-hash admission threshold.
        val result=HashSet<Pair<String,String>>()
        if(ids.size<2)return result
        val size=ids.size
        val tileRows=(2_000_000/size).coerceIn(1,256)
        for(start in 0 until size step tileRows){
            checkActive()
            val end=minOf(start+tileRows,size)
            val votes=ByteArray((end-start)*size)
            for(posting in index.values){
                checkActive()
                if(posting.size !in 2..40)continue
                for(i in 0 until posting.size)for(j in i+1 until posting.size){
                    val first=posting[i]
                    if(first<start||first>=end)continue
                    val offset=(first-start)*size+posting[j]
                    val count=votes[offset].toInt()
                    if(count<90)votes[offset]=(count+1).toByte()
                }
            }
            for(first in start until end){
                checkActive()
                val offset=(first-start)*size
                for(second in first+1 until size)
                    if(votes[offset+second].toInt()>=90)result.add(ids[first] to ids[second])
            }
        }
        return result
    }
}
