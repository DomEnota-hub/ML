package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.media3.common.MediaItem
import java.util.Base64

/** Shared visibility gate for listening history and resumable queues. */
internal class PublicListeningIdentity(context:Context,private val allowed:(MediaItem)->Boolean) {
    private val prefs=context.applicationContext.getSharedPreferences("music_folder_visibility",Context.MODE_PRIVATE)
    private data class Hidden(val ids:Set<String>,val uris:Set<String>)
    private var lastHiddenKeys:Set<String>?=null
    private var lastHidden=Hidden(emptySet(),emptySet())
    private fun hidden():Hidden {
        val keys=prefs.getStringSet("hidden_tracks",emptySet()).orEmpty()
        if(keys==lastHiddenKeys)return lastHidden
        val records=keys.map{it.split('.',limit=2)}.filter{it.size==2}
        lastHiddenKeys=keys.toSet()
        return Hidden(records.map{it[0]}.toSet(),records.map{it[1]}.toSet()).also{lastHidden=it}
    }
    fun visibilityToken():Set<String> { hidden();return lastHiddenKeys.orEmpty() }
    fun keys(item:MediaItem?):Set<String> = keys(item,hidden())
    fun keysForQueue(items:List<MediaItem>):List<Set<String>> {val hidden=hidden();return items.map{keys(it,hidden)}}
    private fun keys(item:MediaItem?,hidden:Hidden):Set<String> {
        if(item==null||!allowed(item)||PrivatePlaybackPolicy.isPrivate(item))return emptySet()
        val extras=item.mediaMetadata.extras ?:return emptySet()
        val uri=extras.getString(AudioPlaybackMetadata.PUBLIC_URI)?:return emptySet()
        if(hidden.ids.isEmpty()&&hidden.uris.isEmpty())return extras.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS).orEmpty().toSet()
        fun encode(value:String)=Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        val aliases=extras.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        val ids=(listOf(item.mediaId)+aliases.filterNot{it.startsWith("uri:")||it.startsWith("file:")||it.startsWith("media:")||it.startsWith("file-stat:")}).map(::encode).toSet()
        val uris=(listOf(uri)+aliases.filter{it.startsWith("uri:")}.map{it.removePrefix("uri:")}).map(::encode).toSet()
        if(ids.any(hidden.ids::contains)||uris.any(hidden.uris::contains))return emptySet()
        return extras.getStringArrayList(AudioPlaybackMetadata.LISTENING_KEYS).orEmpty().toSet()
    }
}
