package com.domenota.medialoader.data.recognition
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.*
import kotlin.random.Random

private fun pcm(kind:String, n:Int):ShortArray {
 val rng=Random(230)
 return ShortArray(n){i->
  val t=i/16000.0
  val noise=rng.nextDouble(-1.0,1.0)
  val note=(t/.23).toInt()
  val beat=t%.23
  val f=doubleArrayOf(261.63,392.0,329.63,523.25,293.66,440.0,349.23,587.33)[note%8]
  val env=exp(-beat*6)*min(1.0,beat*90)
  val melody=env*(sin(2*PI*f*t)+.45*sin(2*PI*2*f*t)+.2*sin(2*PI*3*f*t))
  val bass=0.22*sin(2*PI*110*t)*(0.5+0.5*sin(2*PI*2.2*t))
  val percussion=noise*exp(-((t%.115)*50))*.18
  val v=when(kind){"silence"->0.0;"tone"->sin(2*PI*440*t)*.5;"noise"->noise*.4;"musiclike"->.48*(melody+bass+percussion);else->.26*(melody+bass+percussion)+.15*noise}
  (v*24000).roundToInt().coerceIn(-32768,32767).toShort()
 }
}
private fun verify(s:ShazamSignatureGenerator.Signature,seconds:Int):Pair<Int,Int>{
 val bytes=Base64.getDecoder().decode(s.uri.substringAfter("base64,"));val b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
 check(s.sampleMs==seconds*1000L);check(b.getInt(0)==0xcafe2580L.toInt());check(b.getInt(40)==seconds*16000+3840);check(b.getInt(4).toUInt().toLong()==CRC32().apply{update(bytes,8,bytes.size-8)}.value)
 var pos=56;var peaks=0;var bands=0
 while(pos<bytes.size){val id=b.getInt(pos);check(id in 0x60030040..0x60030043);val size=b.getInt(pos+4);var p=pos+8;val end=p+size;bands++
 while(p<end){val delta=bytes[p].toUByte().toInt();if(delta==255){p+=5}else{peaks++;p+=5}};check(p==end);pos=end+(4-size%4)%4}
 check(pos==bytes.size);return peaks to bands
}
fun main(){
 val kinds=listOf("silence","tone","noise","musiclike","musiclike_noise")
 repeat(2){ShazamSignatureGenerator.generate(pcm("musiclike",64000))}
 println("fixture,seconds,peaks,bands,median_generation_ms,packet_bytes")
 for(kind in kinds){val samples=pcm(kind,64000);for(sec in 1..4){val clip=samples.copyOf(sec*16000);val times=mutableListOf<Double>();var sig=ShazamSignatureGenerator.generate(clip)
 repeat(5){val start=System.nanoTime();val next=ShazamSignatureGenerator.generate(clip);times+=(System.nanoTime()-start)/1e6;check(next==sig);sig=next}
 val (peaks,bands)=verify(sig,sec);val bytes=Base64.getDecoder().decode(sig.uri.substringAfter("base64,"));println("$kind,$sec,$peaks,$bands,${"%.2f".format(java.util.Locale.ROOT,times.sorted()[2])},${bytes.size}")
 }}
}
