package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.*
import androidx.media3.common.util.*
import androidx.media3.effect.*
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/** Sliders change atomically-read parameters; a paused redraw NEVER replaces the effect list.
 * Only crop/rotation/canvas changes recreate the preview player, with position/settings retained.
 */
@OptIn(UnstableApi::class)
internal class VideoEditorPreviewSession(context: Context, source: Uri? = null,
    private val deferPreparation: Boolean = false,
    private val onPlayerCreated: (ExoPlayer) -> Unit = {}) {
    private val context = context.applicationContext
    private val spec = AtomicReference(VideoEditSpec())
    private val clock = VideoPreviewFrameClock()
    private data class Geometry(val crop: List<Float>, val rotation: Int, val mirrors: Pair<Boolean, Boolean>, val ratio: Float?)
    private fun geometry(s: VideoEditSpec, ratio: Float?) = Geometry(
        listOf(s.cropLeft, s.cropRight, s.cropTop, s.cropBottom), s.rotationDegrees,
        s.mirrorHorizontal to s.mirrorVertical, ratio)
    private var geometry = geometry(spec.get(), null)
    private var graph = Graph(spec.get(), geometry.ratio)
    private val _players = MutableStateFlow(build(graph, source?.let { listOf(MediaItem.fromUri(it)) }.orEmpty(), 0, 0))
    val players = _players.asStateFlow()
    val player: ExoPlayer get() = _players.value

    private inner class Graph(initial: VideoEditSpec, ratio: Float?) {
        val fx = VideoSpecialFxOverlay(initial.specialFx, initial.startMs, clock::sourceTimeUs)
        val grain = VideoSpecialFxOverlay(VideoSpecialFxDraft(VideoSpecialFxType.FILM_GRAIN, initial.color.reference.grain / 100), initial.startMs, clock::sourceTimeUs)
        val vignette = VideoSpecialFxOverlay(VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, initial.color.reference.vignette / 100), initial.startMs, clock::sourceTimeUs)
        val text = VideoTextOverlay(context, initial.textLayers, initial.startMs, initial.graphicLayers, clock::sourceTimeUs)
        val effects: List<Effect> = buildList {
            add(PreviewDownscale())
            if (initial.hasCrop) add(Crop(-1f + 2 * initial.cropLeft, 1f - 2 * initial.cropRight,
                -1f + 2 * initial.cropBottom, 1f - 2 * initial.cropTop))
            if (initial.rotationDegrees != 0 || initial.mirrorHorizontal || initial.mirrorVertical)
                add(ScaleAndRotateTransformation.Builder().setRotationDegrees(-initial.rotationDegrees.toFloat())
                    .setScale(if (initial.mirrorHorizontal) -1f else 1f, if (initial.mirrorVertical) -1f else 1f).build())
            add(VideoPreviewColorEffect(context, spec))
            if (ratio != null) add(Presentation.createForAspectRatio(ratio, Presentation.LAYOUT_SCALE_TO_FIT))
            add(OverlayEffect(listOf(fx, grain, vignette, text,
                PreviewTransition(spec, 0, clock), PreviewTransition(spec, 1, clock))))
        }
        fun update(s: VideoEditSpec) {
            fx.update(s.specialFx, s.startMs)
            grain.update(VideoSpecialFxDraft(VideoSpecialFxType.FILM_GRAIN, s.color.reference.grain / 100), s.startMs)
            vignette.update(VideoSpecialFxDraft(VideoSpecialFxType.VIGNETTE, s.color.reference.vignette / 100), s.startMs)
            text.update(s.textLayers, s.graphicLayers, s.startMs)
        }
    }
    private fun build(graph: Graph, items: List<MediaItem>, index: Int, position: Long): ExoPlayer {
        val player = VideoEditorPlayerFactory.builder(context, clock::record)
            .setAudioAttributes(AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA).build(), true).setHandleAudioBecomingNoisy(true).build()
        player.setVideoEffects(graph.effects) // Before the FIRST prepare, including neutral overlays.
        onPlayerCreated(player)
        if (items.isNotEmpty()) {
            player.setMediaItems(items, index, position)
            if (!deferPreparation) player.prepare()
        }
        return player
    }
    fun update(next: VideoEditSpec, canvasRatio: Float? = null) {
        val key = geometry(next, canvasRatio)
        spec.set(next)
        val current = player
        if (key != geometry || current.playerError != null) {
            geometry = key
            replacePlaylist(List(current.mediaItemCount, current::getMediaItemAt),
                current.currentMediaItemIndex, current.currentPosition)
        } else {
            graph.update(next)
            if (!current.playWhenReady) current.setVideoEffects(VideoFrameProcessor.REDRAW)
        }
    }
    /** Media3 can retain an old paused GL output across a clipped-input replacement.
     * Queue changes get a fresh graph; ordinary seeks and slider updates keep the same graph.
     */
    fun replacePlaylist(items: List<MediaItem>, index: Int, positionMs: Long) {
        val current = player
        val playing = current.playWhenReady
        val speed = current.playbackParameters
        val volume = current.volume
        val repeat = current.repeatMode
        current.release()
        clock.clear()
        graph = Graph(spec.get(), geometry.ratio)
        _players.value = build(graph, items, index, positionMs).apply {
            playbackParameters = speed; this.volume = volume; repeatMode = repeat; playWhenReady = playing
        }
    }
    internal fun frameClockSnapshot() = clock.snapshot()
    fun release() { player.release() }
}

@OptIn(UnstableApi::class)
private class PreviewDownscale : GlMatrixTransformation {
    private val identity = GlUtil.create4x4IdentityMatrix()
    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        val scale = (1280f / maxOf(inputWidth, inputHeight)).coerceAtMost(1f)
        return Size((inputWidth * scale).roundToInt().coerceAtLeast(1),
            (inputHeight * scale).roundToInt().coerceAtLeast(1))
    }
    override fun getGlMatrixArray(presentationTimeUs: Long) = identity
    override fun isNoOp(inputWidth: Int, inputHeight: Int) = maxOf(inputWidth, inputHeight) <= 1280
}

/** Reuse the export transition implementation, without re-registering its Media3 stream. */
@OptIn(UnstableApi::class)
private class PreviewTransition(private val spec: AtomicReference<VideoEditSpec>, private val index: Int, private val clock: VideoPreviewFrameClock) : BitmapOverlay() {
    private var size = Size(1, 1)
    private var key: Pair<VideoTransitionPhase, Long>? = null
    private var delegate: VideoTransitionOverlay? = null
    private var empty: Bitmap? = null
    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        size = videoSize; delegate?.release(); delegate = null; key = null
        empty?.recycle(); empty = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val s = spec.get()
        val next = s.transitionPhases.getOrNull(index)?.let { it to s.startMs }
        if (next != key) {
            delegate?.release()
            delegate = next?.let { (phase, offset) -> VideoTransitionOverlay(phase, offset).apply { configure(size) } }
            key = next
        }
        return delegate?.getBitmap(clock.sourceTimeUs(presentationTimeUs)) ?: requireNotNull(empty)
    }
    override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings =
        delegate?.getOverlaySettings(clock.sourceTimeUs(presentationTimeUs)) ?: StaticOverlaySettings.Builder().setAlphaScale(0f).build()
    override fun release() {
        try { super.release() } finally { delegate?.release(); delegate = null; empty?.recycle(); empty = null; key = null }
    }
}
