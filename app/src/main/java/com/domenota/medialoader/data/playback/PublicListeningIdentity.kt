package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.MediaItem
import java.util.Base64

/** Shared visibility gate for listening history and resumable queues. */
internal class PublicListeningIdentity(context:Context,private val allowed:(MediaItem)->Boolean) {
    private val prefs=context.applicationContext.getSharedPreferences("music_folder_visibility",Context.MODE_PRIVATE)
    fun keys(item:MediaItem?):Set<String> {
        if(item==null||!allowed(item)||PrivatePlaybackPolicy.isPrivate(item))return emptySet()
        val extras=item.mediaMetadata.extras ?:return emptySet()
        val uri=extras.getString(AudioPlaybackMetadata.PUBLIC_URI)?:return emptySet()
        fun encode(value:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        val aliases=extras.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        val ids=(listOf(item.mediaId)+aliases.filterNot{it.startsWith("uri:")||it.startsWith("file:")||it.startsWith("media:")||it.startsWith("file-stat:")}).map(::encode).toSet()
        val uris=(listOf(uri)+aliases.filter{it.startsWith("uri:")}.map{it.removePrefix("uri:")}).map(::encode).toSet()
        if(prefs.getStringSet("hidden_tracks",emptySet()).orEmpty().any{record->
            val parts=record.split('.',limit=2)
            parts.size==2&&(parts[0] in ids||parts[1] in uris)
        })return emptySet()
        return extras.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS).orEmpty().toSet()
    }
}
