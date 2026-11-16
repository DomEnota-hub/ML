package com.domenota.medialoader.data.recognition

import android.content.Context
import android.net.Uri
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.data.library.MusicDuplicateScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Writes only temporary copies. Publication is a separate verified transaction. */
internal class AudioMetadataCopyWriter(private val context:Context){
    data class Tagged(val file:File,val format:AudioTagFormat,val newArtworkApplied:Boolean)
    suspend fun write(input:File,result:MusicRecognitionResult,convert:Boolean):Tagged=withContext(Dispatchers.IO){
        val source=AudioFormatProbe.inspect(context,Uri.fromFile(input))
        check(if(convert)source.canConvert else source.tagFormat!=null){"Формат не поддерживает безопасную запись тегов"}
        val format=if(convert)AudioTagFormat.MP3 else requireNotNull(source.tagFormat)
        val writer=Mp3MetadataWriter(context)
        val before=if(!convert)MusicDuplicateScanner.payload(context,Uri.fromFile(input))?:error("Не удалось проверить исходные аудиоданные") else null
        var output:File?=null;var newCover:File?=null;var oldCover:File?=null
        try{
            val originalArtwork=writer.embeddedArtwork(input)
            val unchanged=writer.unchangedTags(input,result)
            val tagged=if(format==AudioTagFormat.MP3&&!convert){
                writer.writePreserving(input,result).let{Tagged(it.file,format,it.newArtworkApplied)}
            }else{
                val target=File.createTempFile("audio-tags-",".${format.extension}",context.cacheDir).also{output=it}
                newCover=writer.preparedArtwork(result.artworkUrl)
                // Preserve the original cover byte-for-byte if no replacement is available.
                if(newCover==null&&originalArtwork!=null){
                    check(originalArtwork.size in 1..10*1024*1024){"Исходная обложка слишком велика для безопасной обработки"}
                    val ext=when{
                        originalArtwork.size>=3 && originalArtwork[0]==(-1).toByte() && originalArtwork[1]==(-40).toByte() && originalArtwork[2]==(-1).toByte()->"jpg"
                        originalArtwork.size>=8 && originalArtwork.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10))->"png"
                        else->error("Формат исходной обложки не поддерживается")
                    }
                    oldCover=File.createTempFile("audio-existing-cover-",".$ext",context.cacheDir).apply{writeBytes(originalArtwork)}
                }
                check(writer.executeCommand(AudioMetadataArguments.build(input.absolutePath,target.absolutePath,format,result,(newCover?:oldCover)?.absolutePath,convert))){"Не удалось безопасно записать теги в ${format.extension.uppercase()}"}
                check(writer.verifiedTags(target,result,newCover!=null,unchanged)&&writer.artworkMatches(target,newCover,originalArtwork)){"Не удалось подтвердить теги и обложку копии"}
                Tagged(target,format,newCover!=null)
            }
            output=tagged.file
            val actual=AudioFormatProbe.inspect(context,Uri.fromFile(tagged.file))
            check(actual.tagFormat==format){"Формат копии не подтверждён"}
            if(convert)check(AudioMetadataFormat.similarDuration(requireNotNull(source.durationMs),requireNotNull(actual.durationMs))){"Длительность копии не совпадает с оригиналом"}
            else check(before==MusicDuplicateScanner.payload(context,Uri.fromFile(tagged.file))){"Звуковые данные копии изменились"}
            tagged
        }catch(t:Throwable){output?.delete();throw t}finally{newCover?.delete();oldCover?.delete()}
    }
}
