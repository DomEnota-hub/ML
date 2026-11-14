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
        private var liveTimer: MusicStopTimer? = null
        private val timerFallback = kotlinx.coroutines.flow.MutableStateFlow(MusicStopStatus())
        internal fun timerState(): kotlinx.coroutines.flow.StateFlow<MusicStopStatus> = liveTimer?.state ?: timerFallback
        internal fun startStopTimer(request: MusicStopRequest): Boolean = liveTimer?.start(request) == true
        internal fun cancelStopTimer() { liveTimer?.cancel() }
        private fun stopGeneration() = liveTimer?.stopGeneration ?: 0L

        /** Comparison uses a separate local player, leaving the service queue and position untouched. */
        internal data class ComparisonPlayback(val player: Player, val ids: List<String>, val index: Int, val position: Long, val wasPlaying: Boolean, val timerGeneration: Long)
        internal fun pauseForComparison(): ComparisonPlayback? {
            val p = liveSession?.player ?: return null
            if (p.currentMediaItem == null) return null
            check(p.currentMediaItem?.mediaMetadata?.extras?.getBoolean("private_media") != true) { "Сначала завершите приватное воспроизведение" }
            return ComparisonPlayback(p, (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId }, p.currentMediaItemIndex, p.currentPosition, p.playWhenReady, stopGeneration()).also { p.pause() }
        }
        internal fun finishComparison(saved: ComparisonPlayback?) {
            val p = liveSession?.player ?: return
            if (saved != null && saved.player === p && saved.wasPlaying && saved.timerGeneration == stopGeneration() && !p.playWhenReady &&
                p.currentMediaItemIndex == saved.index && (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId } == saved.ids &&
                kotlin.math.abs(p.currentPosition - saved.position) < 1000L) p.play()
        }
        internal fun removeTrashedFromQueue(uris: List<String>) {
            val p = liveSession?.player ?: return
            for (index in p.mediaItemCount - 1 downTo 0) {
                val item = p.getMediaItemAt(index)
                if (uris.any { AudioPlaybackMetadata.matchesPublicUri(item,it) }) p.removeMediaItem(index)
            }
        }

        internal data class ReplacementPlayback(val player: Player, val mediaId: String,
            val position: Long, val playWhenReady: Boolean, val timerGeneration: Long)

        internal suspend fun suspendForReplacement(uri: String): ReplacementPlayback? =
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                val player = liveSession?.player ?: return@withContext null
                val current = player.currentMediaItem ?: return@withContext null
                if (!AudioPlaybackMetadata.matchesPublicUri(current, uri)) return@withContext null
                ReplacementPlayback(player, current.mediaId, player.currentPosition.coerceAtLeast(0),
                    player.playWhenReady, stopGeneration()).also { player.pause(); player.stop() }
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
                    player.playWhenReady = suspended.playWhenReady && suspended.timerGeneration == stopGeneration()
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
    private var listeningRecorder: ListeningRecorder? = null
    private var resumeRecorder: ResumeQueueRecorder? = null
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

        val playbackModes = getSharedPreferences("audio_playback_modes", MODE_PRIVATE)
        player.shuffleModeEnabled = playbackModes.getBoolean("shuffle", false)
        player.repeatMode = playbackModes.getInt("repeat", Player.REPEAT_MODE_OFF).let {
            if (it in setOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ONE, Player.REPEAT_MODE_ALL)) it else Player.REPEAT_MODE_OFF
        }
        player.addListener(object : Player.Listener {
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                playbackModes.edit().putBoolean("shuffle", shuffleModeEnabled).apply()
            }
            override fun onRepeatModeChanged(repeatMode: Int) {
                if(liveTimer?.isTemporaryRepeat(repeatMode)!=true) playbackModes.edit().putInt("repeat", repeatMode).apply()
            }
        })

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
        liveTimer = MusicStopTimer(player,privateScope).also { it.attach() }
        val access = PrivateAccessManager.get(this)
        var privateIds: Set<String> = emptySet()
        var privateReady = false
        var guarding = false
        fun guardPrivate() {
            if (guarding) return
            guarding = true
            try { PrivatePlaybackPolicy.protect(player, access.state.value.unlocked, privateIds) }
            finally { guarding = false }
        }
        val allowPublic: (androidx.media3.common.MediaItem)->Boolean = { item ->
            privateReady && !PrivatePlaybackPolicy.isPrivate(item) && item.mediaId !in privateIds &&
                item.mediaMetadata.extras?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty().none(privateIds::contains) &&
                item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.PUBLIC_URI)?.let { uri ->
                    PublicMediaIdentity.uriKeys(uri).none(privateIds::contains)
                } == true
        }
        val listening=ListeningRecorder(this,player,privateScope,ListeningHistoryStore.get(this),allowPublic)
        listeningRecorder = listening
        listening.attach()
        val resume=ResumeQueueRecorder(player,privateScope,ResumeQueueStore.get(this),PublicListeningIdentity(this,allowPublic))
        resumeRecorder=resume;resume.attach()
        privateScope.launch {
            combine(access.state, MediaRepository.get(this@AudioPlaybackService).privateMediaIds) { state, ids -> state to ids }
                .collect { (_, ids) -> privateIds = ids; privateReady = true; guardPrivate(); listening.refresh(); resume.refresh() }
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
        liveTimer?.release(); liveTimer = null
        resumeRecorder?.release(); resumeRecorder=null
        listeningRecorder?.release(); listeningRecorder = null
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
