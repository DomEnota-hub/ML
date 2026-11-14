package com.domenota.medialoader.data.library
import java.util.Random
import kotlin.math.*
fun main() {
 val start = System.nanoTime(); val input = linkedMapOf<String,AcousticFingerprint>()
 for (seed in 0 until 700) {
  val r = Random(seed.toLong()+9183); val notes = DoubleArray(32) { 180+r.nextDouble()*1600 }
  val builder = AcousticFingerprints.Builder()
  for (i in 0 until 8000*12) {
   val t=i/8000.0; val k=(t*2).toInt(); val f=notes[k]
   builder.accept(((sin(2*PI*f*t)+.4*sin(2*PI*f*1.499*t)+.2*sin(2*PI*notes[(k+9)%32]*t))*.3).toFloat())
  }; input["$seed"] = builder.finish()
 }
 input["700"] = input.getValue("17")
 val built = System.nanoTime(); val candidates = AcousticFingerprints.candidatePairs(input); val indexed=System.nanoTime()
 val matches = candidates.filter { (a,b) -> AcousticFingerprints.matches(input.getValue(a),input.getValue(b)) }
 println("tracks=${input.size} landmarks=${input.values.sumOf { it.landmarks.size }} buildMs=${(built-start)/1000000} indexMs=${(indexed-built)/1000000} verifyMs=${(System.nanoTime()-indexed)/1000000} candidates=${candidates.size} matches=${matches.size} duplicateFound=${("17" to "700") in matches}")
 println("heapUsedMB=${(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())/1024/1024}")
 check(("17" to "700") in matches); check(matches.size == 1)
}
