package com.domenota.medialoader.data.library
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
fun main() {
 val fps = linkedMapOf<String,AcousticFingerprint>()
 for (name in listOf("original","mp3","m4a","ogg","trim","negative")) {
  val data = ByteBuffer.wrap(File("/tmp/qa188-audio/$name.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN)
  val start = System.nanoTime(); val b = AcousticFingerprints.Builder()
  while (data.hasRemaining()) b.accept(data.float)
  fps[name] = b.finish(); println("$name landmarks=${fps.getValue(name).landmarks.size} ms=${(System.nanoTime()-start)/1000000}")
 }
 for ((name,fp) in fps) {
  val match = AcousticFingerprints.matches(fps.getValue("original"),fp)
  println("original/$name match=$match")
  check(match == (name != "negative")) { "Unexpected acoustic result for $name" }
 }
 val candidates = AcousticFingerprints.candidatePairs(fps)
 for (name in listOf("mp3","m4a","ogg","trim")) check((minOf(name,"original") to maxOf(name,"original")) in candidates)

 println("pairs=${AcousticFingerprints.candidatePairs(fps)}")
}
