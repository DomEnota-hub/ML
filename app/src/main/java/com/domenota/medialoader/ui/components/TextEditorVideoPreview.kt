package com.domenota.medialoader.ui.components

import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.domenota.medialoader.data.editor.VideoEditSpec
import com.domenota.medialoader.data.editor.VideoEditor

internal data class TextEditorVideoSource(val uri: Uri, val spec: VideoEditSpec)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun rememberTextEditorVideoPlayer(source: TextEditorVideoSource?): ExoPlayer? {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val player = remember(source) {
        source?.let {
            ExoPlayer.Builder(context)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                .setHandleAudioBecomingNoisy(true)
                .build().apply {
                    val clip = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(it.spec.startMs)
                    it.spec.endMs?.let { end -> clip.setEndPositionMs(end) }
                    setMediaItem(MediaItem.Builder().setUri(it.uri).setClippingConfiguration(clip.build()).build())
                    setVideoEffects(VideoEditor(context).previewEffects(it.spec.copy(textLayers = emptyList())))
                    playWhenReady = false
                    prepare()
                }
        }
    }
    DisposableEffect(player, lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) player?.pause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); player?.release() }
    }
    return player
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
internal fun TextEditorVideoSurface(player: ExoPlayer, modifier: Modifier = Modifier) {
    AndroidView(factory = { context ->
        PlayerView(context).apply {
            this.player = player
            useController = false
            isClickable = false
            isFocusable = false
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setBackgroundColor(android.graphics.Color.BLACK)
        }
    }, update = { it.player = player }, onRelease = { it.player = null }, modifier = modifier)
}
