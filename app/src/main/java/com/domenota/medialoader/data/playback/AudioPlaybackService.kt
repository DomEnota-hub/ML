package com.domenota.medialoader.data.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.privatevault.PrivateAccessManager

/** Owns audio playback so music can continue after the viewer leaves the foreground. */
class AudioPlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null
    private val privateScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        val equalizer = EqualizerAudioProcessor()
        val equalizerSettings = EqualizerSettingsRepository.get(this)
        privateScope.launch { equalizerSettings.settings.collect { equalizer.updateSettings(it) } }
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

        mediaSession = MediaSession.Builder(this, player).build()
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
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) { guardPrivate() }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                BackgroundMediaWork.setBusy("audio-playback", isPlaying)
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        privateScope.cancel()
        BackgroundMediaWork.setBusy("audio-playback", false)
        mediaSession?.let { session ->
            session.player.release()
            session.release()
        }
        mediaSession = null
        super.onDestroy()
    }
}
