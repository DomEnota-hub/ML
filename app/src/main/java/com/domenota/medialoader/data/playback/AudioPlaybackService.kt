package com.domenota.medialoader.data.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.privatevault.PrivateAccessManager

/** Owns audio playback so music can continue after the viewer leaves the foreground. */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioPlaybackService : MediaSessionService() {
    companion object {
        private var liveSession: MediaSession? = null

        internal data class ReplacementPlayback(val player: Player, val mediaId: String,
            val position: Long, val playWhenReady: Boolean)

        internal suspend fun suspendForReplacement(uri: String): ReplacementPlayback? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val player = liveSession?.player ?: return@withContext null
                val current = player.currentMediaItem ?: return@withContext null
                if (!AudioPlaybackMetadata.matchesPublicUri(current, uri)) return@withContext null
                ReplacementPlayback(player, current.mediaId, player.currentPosition.coerceAtLeast(0),
                    player.playWhenReady).also { player.pause(); player.stop() }
            }

        internal suspend fun finishReplacement(uri: String, revised: com.domenota.medialoader.core.database.DownloadEntity?,
            suspended: ReplacementPlayback?) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val player = liveSession?.player ?: return@withContext
                if (revised != null) for (index in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(index)
                    if (AudioPlaybackMetadata.matchesPublicUri(item, uri))
                        player.replaceMediaItem(index, AudioPlaybackMetadata.revisedItem(item, revised))
                }
                if (suspended != null && player === suspended.player &&
                    player.currentMediaItem?.mediaId == suspended.mediaId &&
                    player.currentMediaItem?.let { AudioPlaybackMetadata.matchesPublicUri(it, uri) } == true) {
                    val limit = revised?.durationMs?.coerceAtLeast(0L) ?: Long.MAX_VALUE
                    player.seekTo(suspended.position.coerceAtMost(limit))
                    player.prepare()
                    player.playWhenReady = suspended.playWhenReady
                }
            }

        internal suspend fun requireNoLegacyQueueUri(oldUri: String, newUri: String) =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val player = liveSession?.player ?: return@withContext
                for (index in 0 until player.mediaItemCount) {
                    val item = player.getMediaItemAt(index)
                    val actual = item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)
                        ?: item.localConfiguration?.uri?.toString()
                    check(actual != oldUri || actual == newUri) { "В очереди воспроизведения осталась старая ссылка" }
                }
            }

        /** Runs against the service player itself; a controller may expose optimistic state. */
        internal suspend fun clearHistoricalPlayback(): Unit = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
            check(com.domenota.medialoader.data.storage.HistoricalRecoverySignals.isRunning.value)
            liveSession?.player?.let { player ->
                player.pause()
                player.stop()
                player.clearMediaItems()
                check(player.mediaItemCount == 0 && !player.isPlaying && player.playbackState == Player.STATE_IDLE) {
                    "Не удалось остановить воспроизведение перед восстановлением файлов"
                }
            }
            Unit
        }
    }

    private var mediaSession: MediaSession? = null
    private val privateScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val equalizer = EqualizerAudioProcessor()
        val equalizerSettings = EqualizerSettingsRepository.get(this)
        val player = ExoPlayer.Builder(this, EqualizerRenderersFactory(this, equalizer))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        fun refreshEqualizer() {
            val track = EqualizerTrack.fromMediaItem(player.currentMediaItem)
            // Always select against the latest repository snapshot on the service main
            // thread. Transition callbacks cannot carry a previous track's settings.
            equalizer.updateSettings(equalizerSettings.state.value.effective(track))
            EqualizerPlaybackTrack.update(track)
        }
        privateScope.launch { equalizerSettings.state.collect { refreshEqualizer() } }

        mediaSession = MediaSession.Builder(this, player).build()
        liveSession = mediaSession
        val access = PrivateAccessManager.get(this)
        var privateIds: Set<String> = emptySet()
        var guarding = false
        fun guardPrivate() {
            if (guarding) return
            guarding = true
            try { PrivatePlaybackPolicy.protect(player, access.state.value.unlocked, privateIds) }
            finally { guarding = false }
        }
        privateScope.launch {
            combine(access.state, MediaRepository.get(this@AudioPlaybackService).privateMediaIds) { state, ids -> state to ids }
                .collect { (_, ids) -> privateIds = ids; guardPrivate() }
        }
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                com.domenota.medialoader.core.logging.AppLog.w("Audio", "Playback failed · code=${error.errorCodeName} · cause=${error.cause?.javaClass?.simpleName.orEmpty()}")
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
                guardPrivate()
                refreshEqualizer()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                BackgroundMediaWork.setBusy("audio-playback", isPlaying)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        privateScope.cancel()
        EqualizerPlaybackTrack.update(null)
        BackgroundMediaWork.setBusy("audio-playback", false)
        if (liveSession === mediaSession) liveSession = null
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
