package com.domenota.medialoader.data.catalog.visibility

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/**
 * Narrow fallback for a missing/generic device MIME mapping. Read-only, bounded,
 * no links, no extension-only acceptance. It identifies a supported container;
 * it is not a decoder or proof that all samples are playable. Exact physical
 * inventory checks still gate marker creation independently.
 */
internal object NomediaMediaProbe {
    private val generic = setOf("application/octet-stream","application/unknown")
    private val brands = setOf("isom","iso2","iso3","iso4","iso5","iso6","mp41","mp42","avc1","dash","M4V ","M4A ")
    private val sof = setOf(0xc0,0xc1,0xc2,0xc3,0xc5,0xc6,0xc7,0xc9,0xca,0xcb,0xcd,0xce,0xcf)

    fun fallback(path:Path,extension:String,reported:String?):String? {
        if(reported!=null && reported !in generic)return null
        if(extension !in setOf("jpg","jpeg","mp4"))return null
        return try {
            val before=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            if(!before.isRegularFile || before.isSymbolicLink || before.size()<16)return null
            val type=FileChannel.open(path,READ,NOFOLLOW_LINKS).use { channel ->
                if(channel.size()!=before.size())return null
                when(extension){
                    "jpg","jpeg" -> if(jpeg(channel,before.size()))"image/jpeg" else null
                    "mp4" -> if(mp4(channel,before.size()))"video/mp4" else null
                    else -> null
                }
            }
            val after=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
            type?.takeIf{after.isRegularFile && !after.isSymbolicLink &&
                before.size()==after.size() && before.lastModifiedTime()==after.lastModifiedTime() &&
                before.fileKey()==after.fileKey()}
        }catch(_:Exception){null}
    }

    private fun bytes(channel:FileChannel,at:Long,count:Int):ByteArray? {
        if(at<0||count<0||at>channel.size()-count)return null
        val buffer=ByteBuffer.allocate(count)
        var position=at
        while(buffer.hasRemaining()){
            val n=channel.read(buffer,position)
            if(n<=0)return null
            position+=n
        }
        return buffer.array()
    }
    private fun ByteArray.u(i:Int)=this[i].toInt() and 255
    private fun ByteArray.u16(i:Int)=(u(i) shl 8) or u(i+1)
    private fun ByteArray.fourcc(i:Int)=String(this,i,4,Charsets.US_ASCII)

    private fun jpeg(channel:FileChannel,size:Long):Boolean {
        val head=bytes(channel,0,minOf(size,65536L).toInt())?:return false
        val tail=bytes(channel,size-2,2)?:return false
        if(head.u(0)!=0xff||head.u(1)!=0xd8||tail.u16(0)!=0xffd9)return false
        var offset=2
        var dimensions=false
        while(offset<head.size){
            if(head.u(offset++)!=0xff)return false
            while(offset<head.size&&head.u(offset)==0xff)offset++
            if(offset>=head.size)return false
            val marker=head.u(offset++)
            if(offset+2>head.size)return false
            val length=head.u16(offset)
            if(length<2||offset+length>head.size)return false
            if(marker in sof){
                if(length<8)return false
                val components=head.u(offset+7)
                dimensions=head.u16(offset+3)>0&&head.u16(offset+5)>0&&
                    components in 1..4&&length==8+3*components
            }
            if(marker==0xda){
                if(length<6)return false
                val components=head.u(offset+2)
                return dimensions&&components in 1..4&&length==6+2*components&&
                    offset.toLong()+length<size-2
            }
            if(marker==0xd9||marker==0xd8||marker in 0xd0..0xd7)return false
            offset+=length
        }
        return false
    }

    private fun mp4(channel:FileChannel,size:Long):Boolean {
        var at=0L
        var ftyp=false
        var moov=false
        var mdat=false
        repeat(256){
            if(at==size)return ftyp&&moov&&mdat
            val h=bytes(channel,at,8)?:return false
            val small=ByteBuffer.wrap(h).int.toLong() and 0xffffffffL
            val type=h.fourcc(4)
            val header=if(small==1L)16 else 8
            val box=when(small){
                0L -> size-at
                1L -> ByteBuffer.wrap(bytes(channel,at+8,8)?:return false).long
                else -> small
            }
            if(box<header||box>size-at)return false
            when(type){
                "ftyp" -> {
                    if(ftyp||at!=0L||box-header !in 8L..4096L||(box-header)%4L!=0L)return false
                    val payload=bytes(channel,at+header,(box-header).toInt())?:return false
                    ftyp=payload.fourcc(0) in brands||(8 until payload.size step 4).any{payload.fourcc(it) in brands}
                    if(!ftyp)return false
                }
                "moov" -> moov=box>header
                "mdat" -> mdat=box>header
            }
            if(!ftyp)return false
            at+=box
        }
        return at==size&&ftyp&&moov&&mdat
    }
}
