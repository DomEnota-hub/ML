package com.domenota.medialoader.data.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.playback.PublicMediaIdentity

/** MediaStore may defer or ignore title/artist writes. Only explicitly edited MP3s
 * are re-read; no names or guessed metadata are persisted in an override cache. */
internal object AudioEditedTags {
    private fun prefs(context: Context) = context.getSharedPreferences("edited_audio_id3", Context.MODE_PRIVATE)
    private fun key(uri: String) = PublicMediaIdentity.uriKeys(uri).firstOrNull { it.startsWith("media:") } ?: "uri:$uri"
    fun marked(context: Context): Set<String> = prefs(context).getStringSet("keys", emptySet()).orEmpty().toSet()
    @Synchronized fun mark(context: Context, uri: String) {
        val keys = marked(context) + key(uri)
        check(prefs(context).edit().putStringSet("keys", keys).commit()) { "Не сохранён индекс отредактированного MP3" }
    }
    fun read(context: Context, row: DownloadEntity, marked: Set<String>): DownloadEntity {
        val uri = row.savedUri ?: return row
        if (row.isPrivate || row.hidden || key(uri) !in marked || !row.originalName.endsWith(".mp3", true)) return row
        return runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, Uri.parse(uri))
                val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                val album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                row.copy(sourceAudioTitle = title, sourceAudioArtist = artist, recognizedAlbum = album,
                    durationMs = duration ?: row.durationMs)
            } finally { retriever.release() }
        }.getOrDefault(row)
    }
}
