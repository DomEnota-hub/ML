package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.roundToInt

@OptIn(UnstableApi::class)
internal class VideoTextOverlay(private val context: Context, layers: List<TextLayer>,
    timeOffsetMs: Long = 0L,
    graphics: List<VideoGraphicLayer> = emptyList()) : BitmapOverlay() {
    private data class State(val layers: List<TextLayer>, val graphics: List<VideoGraphicLayer>, val offset: Long)
    private val state = java.util.concurrent.atomic.AtomicReference(State(layers, graphics, timeOffsetMs))
    private var applied: State? = null
    private var videoWidth = 1
    private var videoHeight = 1
    private var scale = 1f
    fun update(layers: List<TextLayer>, graphics: List<VideoGraphicLayer>, timeOffsetMs: Long) {
        state.set(State(layers, graphics, timeOffsetMs))
    }
    private val graphicRenderer = VideoGraphicRenderer(context)
    private var image: Bitmap? = null
    private var settings = StaticOverlaySettings.Builder().build()
    private var activeIds: List<String>? = null
    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        scale = (1920f / maxOf(videoSize.width, videoSize.height)).coerceAtMost(1f)
        videoWidth = (videoSize.width * scale).roundToInt().coerceAtLeast(1)
        videoHeight = (videoSize.height * scale).roundToInt().coerceAtLeast(1)
        image?.recycle(); image = null; applied = null
        activeIds = null
    }
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val current = state.get()
        if (current != applied || image == null) {
            val hasLayers = current.layers.isNotEmpty() || current.graphics.isNotEmpty()
            if (image == null || hasLayers && (image!!.width != videoWidth || image!!.height != videoHeight)) {
                image?.recycle()
                image = Bitmap.createBitmap(if (hasLayers) videoWidth else 1,
                    if (hasLayers) videoHeight else 1, Bitmap.Config.ARGB_8888)
            }
            if (applied?.graphics != current.graphics) graphicRenderer.prepare(current.graphics)
            settings = StaticOverlaySettings.Builder().setScale(1f / scale, 1f / scale).build()
            applied = current; activeIds = null
        }
        val bitmap = requireNotNull(image)
        val localMs = presentationTimeUs / 1000L - current.offset
        val activeText = current.layers.filter { it.visibleAt(localMs) }
        val activeGraphics = current.graphics.filter { it.visibleAt(localMs) }
        val keys = activeText.map { "text:${it.id}" } +
            activeGraphics.map { "graphic:${it.id}" }
        if (activeIds != keys) {
            bitmap.eraseColor(Color.TRANSPARENT)
            val canvas = Canvas(bitmap)
            graphicRenderer.draw(canvas, bitmap.width, bitmap.height, activeGraphics, localMs)
            TextLayerRenderer.draw(context, canvas, bitmap.width, bitmap.height,
                activeText, positionMs = localMs)
            activeIds = keys
        }
        return bitmap
    }
    override fun getOverlaySettings(presentationTimeUs: Long) = settings
    override fun release() {
        try { super.release() } finally {
            image?.recycle(); image = null; applied = null
            graphicRenderer.release()
        }
    }
}
