package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Replay is enabled ONLY for the editor, never for ordinary/music playback. */
@OptIn(UnstableApi::class)
internal object VideoEditorPlayerFactory {
    fun builder(context: Context): ExoPlayer.Builder = ExoPlayer.Builder(context)
        .setRenderersFactory(EditingRenderersFactory(context))

    /** A new effect list registers a NEW input stream and invalidates Media3's replay cache.
     * REDRAW alone cannot supply the first decoded frame to that new stream on pause.
     * Force a decoder seek then restore the exact logical position, without playing audio.
     */
    fun updateEffects(player: ExoPlayer, effects: List<androidx.media3.common.Effect>) {
        player.setVideoEffects(effects)
        if (!player.playWhenReady && player.mediaItemCount > 0) {
            val index = player.currentMediaItemIndex
            val position = player.currentPosition
            val nearby = if (position > 0L) position - 1L else 1L
            player.seekTo(index, nearby)
            player.seekTo(index, position)
        }
    }

    private class EditingRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(context: Context, extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector, enableDecoderFallback: Boolean,
            eventHandler: Handler, eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long, out: ArrayList<Renderer>) {
            // The editor uses Android's platform codecs; default audio renderers are unchanged.
            out.add(EditingVideoRenderer(MediaCodecVideoRenderer.Builder(context)
                .setCodecAdapterFactory(getCodecAdapterFactory())
                .setMediaCodecSelector(mediaCodecSelector)
                .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                .setEnableDecoderFallback(enableDecoderFallback)
                .setEventHandler(eventHandler)
                .setEventListener(eventListener)
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)))
        }
    }

    private class EditingVideoRenderer(builder: MediaCodecVideoRenderer.Builder) :
        MediaCodecVideoRenderer(builder) {
        override fun createPlaybackVideoGraphWrapper(context: Context,
            videoFrameReleaseControl: VideoFrameReleaseControl): PlaybackVideoGraphWrapper =
            PlaybackVideoGraphWrapper.Builder(context, videoFrameReleaseControl)
                .setEnablePlaylistMode(true)
                // REDRAW throws UnsupportedOperationException without this flag in Media3 1.9.0.
                // Upstream ReplayableFrameCacheGlShaderProgram bounds this GPU cache to 2 frames.
                .setEnableReplayableCache(true)
                .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET)
                .setClock(getClock())
                .build()
    }
}
