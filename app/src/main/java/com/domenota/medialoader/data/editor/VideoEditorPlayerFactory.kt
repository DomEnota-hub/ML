package com.domenota.medialoader.data.editor

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.common.Format
import java.nio.ByteBuffer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.PlaybackVideoGraphWrapper
import androidx.media3.exoplayer.video.VideoFrameReleaseControl
import androidx.media3.exoplayer.video.VideoRendererEventListener

/** Replay is enabled ONLY for the editor, never for ordinary/music playback. */
@OptIn(UnstableApi::class)
internal object VideoEditorPlayerFactory {
    fun builder(context: Context, onFrame: (Long, Long) -> Unit = { _, _ -> }): ExoPlayer.Builder = ExoPlayer.Builder(context)
        .setRenderersFactory(EditingRenderersFactory(context, onFrame))

    private class EditingRenderersFactory(context: Context, private val onFrame: (Long, Long) -> Unit) : DefaultRenderersFactory(context) {
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
                .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY), onFrame))
        }
    }

    private class EditingVideoRenderer(builder: MediaCodecVideoRenderer.Builder,
        private val onFrame: (Long, Long) -> Unit) : MediaCodecVideoRenderer(builder) {
        override fun processOutputBuffer(positionUs: Long, elapsedRealtimeUs: Long,
            codec: MediaCodecAdapter?, buffer: ByteBuffer?, bufferIndex: Int, bufferFlags: Int,
            sampleCount: Int, bufferPresentationTimeUs: Long, isDecodeOnlyBuffer: Boolean,
            isLastBuffer: Boolean, format: Format): Boolean {
            // Must precede surface submission: GL may consume it immediately.
            onFrame(bufferPresentationTimeUs + getBufferTimestampAdjustmentUs(),
                bufferPresentationTimeUs - getOutputStreamOffsetUs())
            return super.processOutputBuffer(positionUs, elapsedRealtimeUs, codec, buffer,
                bufferIndex, bufferFlags, sampleCount, bufferPresentationTimeUs,
                isDecodeOnlyBuffer, isLastBuffer, format)
        }

        // Media3 1.9 exposes replay-cache configuration only through this library-group
        // hook. Version is pinned; real paused/playing codec tests guard the integration.
        // Restriction is local to this override, not a lint baseline or runtime SDK bypass.
        @android.annotation.SuppressLint("RestrictedApi")
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
