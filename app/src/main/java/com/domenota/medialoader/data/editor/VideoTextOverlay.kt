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
internal class VideoTextOverlay(private val context: Context, private val layers: List<TextLayer>,
    private val timeOffsetMs: Long = 0L) : BitmapOverlay() {
    private var image: Bitmap? = null
    private var settings = StaticOverlaySettings.Builder().build()
    private var activeIds: List<String>? = null
    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        val scale = (1920f / maxOf(videoSize.width, videoSize.height)).coerceAtMost(1f)
        image?.recycle()
        image = Bitmap.createBitmap((videoSize.width * scale).roundToInt().coerceAtLeast(1),
            (videoSize.height * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        settings = StaticOverlaySettings.Builder().setScale(1f / scale, 1f / scale).build()
        activeIds = null
    }
    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val bitmap = requireNotNull(image)
        val active = layers.filter { it.visibleAt(presentationTimeUs / 1000L - timeOffsetMs) }
        if (activeIds != active.map { it.id }) {
            bitmap.eraseColor(Color.TRANSPARENT)
            TextLayerRenderer.draw(context, Canvas(bitmap), bitmap.width, bitmap.height, active, includeAll = true)
            activeIds = active.map { it.id }
        }
        return bitmap
    }
    override fun getOverlaySettings(presentationTimeUs: Long) = settings
    override fun release() {
        try { super.release() } finally { image?.recycle(); image = null }
    }
}
