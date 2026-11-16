package com.domenota.medialoader.data.recognition

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AudioFormatProbe {
    suspend fun inspect(context:Context,uri:Uri):AudioFileFormat=withContext(Dispatchers.IO){
        val container=(if(uri.scheme=="file")File(requireNotNull(uri.path)).inputStream() else context.contentResolver.openInputStream(uri)?:error("Файл недоступен")).use(AudioMetadataFormat::container)
        val ex=MediaExtractor();val reader=MediaMetadataRetriever()
        try{
            ex.setDataSource(context,uri,null);reader.setDataSource(context,uri)
            val tracks=(0 until ex.trackCount).map{ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)}
            val duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val video=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)=="yes"
            AudioMetadataFormat.classify(container,tracks,duration,video)
        }finally{ex.release();reader.release()}
    }
}
