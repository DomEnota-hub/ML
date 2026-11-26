package com.domenota.medialoader.data.playback

import android.content.Context
import com.domenota.medialoader.data.tasks.*
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MusicLoudnessPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("music_loudness", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(prefs.getBoolean("enabled", false))
    val enabled = mutable.asStateFlow()
    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        mutable.value = value
    }
    companion object {
        @Volatile private var instance: MusicLoudnessPreferences? = null
        fun get(context: Context): MusicLoudnessPreferences = instance ?: synchronized(this) {
            instance ?: MusicLoudnessPreferences(context).also { instance = it }
        }
    }
}

/** No persistent derived media data; only the current/next authorized local item is measured.
 * One decoder at a time, cancelled on queue/access changes. The session/player stays authoritative.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicLoudness(
    private val context: Context,
    private val player: Player,
    private val processor: EqualizerAudioProcessor,
    private val scope: CoroutineScope,
    private val preferences: MusicLoudnessPreferences,
    private val allowPublic: (MediaItem) -> Boolean,
) : Player.Listener {
    private data class Source(val uri: Uri, val revision: String?, val id: String)
    private var desired = emptyList<Source>()
    private val measurements = mutableMapOf<Source, MusicLoudnessMeasurement?>()
    private var analysis: Job? = null
    private var observer: Job? = null
    private val serial = Mutex()

    private fun source(item: MediaItem?): Source? {
        if (item == null || !allowPublic(item) || PrivatePlaybackPolicy.isPrivate(item)) return null
        val uri = item.localConfiguration?.uri ?: return null
        if (uri.scheme !in setOf("file", "content")) return null
        return Source(uri, item.mediaMetadata.extras?.getString(AudioPlaybackMetadata.CONTENT_REVISION), item.mediaId)
    }

    fun gainFor(item: MediaItem?): Double {
        if (!preferences.enabled.value) return 1.0
        return MusicLoudnessPolicy.gain(source(item)?.let { measurements[it] })
    }

    private fun candidates(): List<Source> {
        if (!preferences.enabled.value ||
            player.playbackState == Player.STATE_IDLE || player.playbackState == Player.STATE_ENDED) return emptyList()
        val current = source(player.currentMediaItem) ?: return emptyList()
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return listOf(current)
        val next = timeline.getNextWindowIndex(player.currentMediaItemIndex, player.repeatMode, player.shuffleModeEnabled)
        val upcoming = if (next in 0 until player.mediaItemCount) source(player.getMediaItemAt(next)) else null
        return listOfNotNull(current, upcoming).distinct()
    }

    private fun updateGain() { processor.updateNormalizationGain(gainFor(player.currentMediaItem)) }

    private fun refresh() {
        val next = candidates()
        if (next != desired) {
            analysis?.cancel()
            analysis = null
            desired = next
            // Never keep a library-wide cache, and never reuse an unknown revision after leaving the pair.
            measurements.keys.retainAll(next.toSet())
        }
        // Pause cancels analysis but keeps completed measurements for this exact pair.
        if (!player.playWhenReady) {
            analysis?.cancel()
            analysis = null
        } else if (analysis?.isActive != true && next.any { !measurements.containsKey(it) }) {
            analysis = scope.launch {
                val pending = next.filterNot { measurements.containsKey(it) }
                val reporter = TaskReporter.create(TaskOwner.LOUDNESS, TaskKind.LOUDNESS)
                var done=0
                var failures=0
                reporter.report(files=countProgress(done,pending.size),stage=TaskStage.SCANNING)
                try {
                for (source in next) {
                    if (measurements.containsKey(source)) continue
                    val result = serial.withLock {
                        if (source !in candidates() || !player.playWhenReady) null
                        else MusicLoudnessAnalyzer.analyze(context, source.uri)
                    }
                    ensureActive()
                    if (!player.playWhenReady || source !in desired || source !in candidates()) return@launch
                    measurements[source] = result
                    done++
                    if(result==null) failures++
                    reporter.report(files=countProgress(done,pending.size),errors=failures,stage=TaskStage.SCANNING)
                    updateGain()
                }
                reporter.report(if(failures>0) TaskStatus.PARTIAL else TaskStatus.COMPLETE,files=countProgress(done,pending.size),errors=failures)
                } catch (e: CancellationException) { reporter.remove(); throw e }
                catch (e: Exception) { reporter.report(TaskStatus.FAILED,files=countProgress(done,pending.size),errors=failures+1); throw e }
                finally { if(done<pending.size) reporter.remove() }
            }
        }
        updateGain()
    }

    fun attach() {
        player.addListener(this)
        observer = scope.launch { preferences.enabled.collect { refresh() } }
        refresh()
    }

    /** A public/private transition invalidates all derived values, even when identity aliases change. */
    fun invalidateAccess() {
        analysis?.cancel()
        analysis = null
        desired = emptyList()
        measurements.clear()
        updateGain()
        refresh()
    }
    override fun onEvents(player: Player, events: Player.Events) { refresh() }
    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) invalidateAccess()
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { updateGain() }
    fun release() {
        player.removeListener(this)
        observer?.cancel(); analysis?.cancel()
        desired = emptyList(); measurements.clear()
        processor.updateNormalizationGain(1.0)
    }
}
