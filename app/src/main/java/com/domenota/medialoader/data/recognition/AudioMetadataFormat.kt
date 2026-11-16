package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import java.io.InputStream

/** Container evidence comes from the file, never its extension or provider MIME. */
internal enum class AudioContainer(val label:String) { MP3("MP3"), MP4("MP4"), FLAC("FLAC"), OGG("Ogg"), WAV("WAV"), AAC("AAC/ADTS"), UNKNOWN("Неизвестный контейнер") }
internal enum class AudioTagFormat(val extension:String,val mime:String) { MP3("mp3","audio/mpeg"), M4A("m4a","audio/mp4"), FLAC("flac","audio/flac") }
internal enum class AudioMetadataAction { REPLACE_MP3, TAGGED_COPY, CONVERT_MP3, UNAVAILABLE }
internal data class AudioFileFormat(val container:AudioContainer,val codec:String?,val durationMs:Long?,val singleAudio:Boolean) {
    val tagFormat:AudioTagFormat? get()=if(!singleAudio)null else when {
        container==AudioContainer.MP3 && codec=="audio/mpeg" -> AudioTagFormat.MP3
        container==AudioContainer.MP4 && codec in setOf("audio/mp4a-latm","audio/alac") -> AudioTagFormat.M4A
        container==AudioContainer.FLAC && codec=="audio/flac" -> AudioTagFormat.FLAC
        else -> null
    }
    val canConvert get()=singleAudio && durationMs!=null && durationMs>0
    fun action(replaceEligible:Boolean)=when {
        tagFormat==AudioTagFormat.MP3 && replaceEligible -> AudioMetadataAction.REPLACE_MP3
        tagFormat!=null -> AudioMetadataAction.TAGGED_COPY
        canConvert -> AudioMetadataAction.CONVERT_MP3
        else -> AudioMetadataAction.UNAVAILABLE
    }
    val label get()=container.label + (codec?.let{" · $it"} ?: "")
}
internal object AudioMetadataFormat {
    private fun u(b:Byte)=b.toInt() and 255
    private fun read(input:InputStream,count:Int):ByteArray {
        val bytes=ByteArray(count);var n=0
        while(n<count){val k=input.read(bytes,n,count-n);if(k<0)break;if(k==0)break;n+=k}
        return bytes.copyOf(n)
    }
    private fun skip(input:InputStream,count:Long):Boolean {
        var remaining=count
        while(remaining>0){val n=input.skip(remaining);if(n>0)remaining-=n else if(input.read()<0)return false else remaining--}
        return true
    }
    private fun mp3Length(b:ByteArray,offset:Int):Int? {
        if(offset<0||offset+4>b.size)return null
        val h=(0..3).fold(0){v,i->(v shl 8) or u(b[offset+i])}
        if(h ushr 21!=0x7ff || (h ushr 17 and 3)!=1)return null
        val v=h ushr 19 and 3;val r=h ushr 10 and 3;val k=h ushr 12 and 15
        if(v==1||r>=3||k !in 1..14)return null
        val rate=intArrayOf(44100,48000,32000)[r]/when(v){3->1;2->2;else->4}
        val bitrate=(if(v==3)intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320) else intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160))[k]
        return (if(v==3)144000 else 72000)*bitrate/rate+(h ushr 9 and 1)
    }
    fun container(input:InputStream):AudioContainer {
        val stream=input.buffered();stream.mark(10);val first=read(stream,10);stream.reset()
        if(first.size==10 && String(first,0,3,Charsets.US_ASCII)=="ID3"){
            if(u(first[3]) !in 2..4 || (6..9).any{u(first[it])>=128})return AudioContainer.UNKNOWN
            val size=(6..9).fold(0L){v,i->(v shl 7) or u(first[i]).toLong()}
            if(size>32L*1024*1024 || !skip(stream,10+size+if(u(first[3])==4 && u(first[5]) and 16!=0)10 else 0))return AudioContainer.UNKNOWN
        }
        val b=read(stream,4096)
        fun magic(offset:Int,text:String)=b.size>=offset+text.length && String(b,offset,text.length,Charsets.US_ASCII)==text
        val frame=mp3Length(b,0)
        return when {
            magic(4,"ftyp")->AudioContainer.MP4
            magic(0,"fLaC")->AudioContainer.FLAC
            magic(0,"OggS")->AudioContainer.OGG
            magic(0,"RIFF")&&magic(8,"WAVE")->AudioContainer.WAV
            frame!=null && mp3Length(b,frame)?.let{frame+it<=b.size}==true->AudioContainer.MP3
            b.size>=7 && u(b[0])==255 && u(b[1]) and 0xf6==0xf0->AudioContainer.AAC
            else->AudioContainer.UNKNOWN
        }
    }
    fun classify(container:AudioContainer,tracks:List<String?>,durationMs:Long?,hasVideo:Boolean):AudioFileFormat {
        val audio=tracks.filter{it?.startsWith("audio/")==true}
        return AudioFileFormat(container,audio.singleOrNull(),durationMs,
            !hasVideo && audio.size==1 && tracks.all{it?.startsWith("audio/")==true || it?.startsWith("image/")==true})
    }
    fun similarDuration(before:Long,after:Long)=before>0 && after>0 && kotlin.math.abs(before-after)<= (before/200).coerceIn(250,1000)
}
internal object AudioMetadataArguments {
    fun build(input:String,output:String,format:AudioTagFormat,result:MusicRecognitionResult,artwork:String?,convert:Boolean):List<String> = buildList {
        require(!convert || format==AudioTagFormat.MP3)
        addAll(listOf("-nostdin","-hide_banner","-loglevel","error","-y","-i",input))
        if(artwork!=null)addAll(listOf("-i",artwork))
        addAll(listOf("-map","0:a:0","-map_metadata","0","-c:a",if(convert)"libmp3lame" else "copy"))
        if(convert)addAll(listOf("-b:a","192k"))
        if(artwork!=null)addAll(listOf("-map","1:v:0","-c:v","copy","-disposition:v:0","attached_pic","-metadata:s:v:0","title=Album cover","-metadata:s:v:0","comment=Cover (front)")) else add("-vn")
        if(format==AudioTagFormat.MP3)addAll(listOf("-id3v2_version","3","-write_id3v1","0"))
        addAll(listOf("-metadata","title=${result.title}","-metadata","artist=${result.artist}"))
        result.album?.takeIf{it.isNotBlank()}?.let{addAll(listOf("-metadata","album=$it"))}
        add(output)
    }
}
