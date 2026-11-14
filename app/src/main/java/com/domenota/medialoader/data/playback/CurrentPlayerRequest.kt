package com.domenota.medialoader.data.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.MediaItem
import com.domenota.medialoader.MainActivity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType

/** An intent selects the current service player, never a copied track or queue. */
internal object CurrentPlayerRequest {
    const val ACTION = "com.domenota.medialoader.OPEN_CURRENT_PLAYER"
    private const val REQUEST_CODE = 5043
    fun intent(context: Context): Intent = Intent(context, MainActivity::class.java)
        .setAction(ACTION).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getActivity(context, REQUEST_CODE,
        intent(context), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}

/** Retain pending requests across recreation; consuming an older request cannot erase a newer tap. */
internal data class CurrentPlayerRequestState(val generation: Int = 0, val pending: Int = 0) {
    fun accept(action: String?): CurrentPlayerRequestState {
        if(action != CurrentPlayerRequest.ACTION) return this
        val next=if(generation==Int.MAX_VALUE)1 else generation+1
        return CurrentPlayerRequestState(next,next)
    }
    fun consume(request: Int): CurrentPlayerRequestState = if(request>0 && pending==request) copy(pending=0) else this
    fun save(bundle: Bundle) {bundle.putInt("current_player_generation",generation);bundle.putInt("current_player_pending",pending)}
    companion object {
        fun restore(bundle: Bundle?): CurrentPlayerRequestState = CurrentPlayerRequestState(
            bundle?.getInt("current_player_generation")?.coerceAtLeast(0) ?: 0,
            bundle?.getInt("current_player_pending")?.coerceAtLeast(0) ?: 0)
    }
}

internal sealed interface CurrentPlayerDestination {
    data object Wait: CurrentPlayerDestination
    data object Unavailable: CurrentPlayerDestination
    data class Open(val row: DownloadEntity): CurrentPlayerDestination
}

/** Pure navigation decision. It has no player mutation API and never guesses a track from its title. */
internal object CurrentPlayerNavigation {
    fun resolve(controllerReady:Boolean,catalogReady:Boolean,privateReady:Boolean,currentItem:MediaItem?,
        rows:List<DownloadEntity>,blockedIdentities:Set<String>):CurrentPlayerDestination {
        if(!controllerReady || !catalogReady || !privateReady)return CurrentPlayerDestination.Wait
        val media=currentItem ?: return CurrentPlayerDestination.Unavailable
        if(AudioPlaybackMetadata.publicRow(media,emptyList(),blockedIdentities)==null)return CurrentPlayerDestination.Unavailable
        val identities=media.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        val uri=media.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI) ?: media.localConfiguration?.uri?.toString()
        val eligible=rows.filter { !it.isPrivate && !it.hidden && it.mediaType==MediaType.AUDIO &&
            it.state==DownloadState.COMPLETED && !it.savedUri.isNullOrBlank() && it.id !in blockedIdentities &&
            PublicMediaIdentity.uriKeys(requireNotNull(it.savedUri)).none(blockedIdentities::contains) &&
            AudioPlaybackMetadata.matchesPublicUri(media,requireNotNull(it.savedUri)) }
        val exact=eligible.filter { it.id==media.mediaId }.distinctBy { it.id }
        val matches=if(exact.isNotEmpty())exact else eligible.filter { it.id in identities || (uri!=null && it.savedUri==uri) }.distinctBy { it.id }
        return matches.singleOrNull()?.let { CurrentPlayerDestination.Open(it) } ?: CurrentPlayerDestination.Unavailable
    }
}
