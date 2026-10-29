package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.Matrix
import android.text.TextPaint
import android.text.StaticLayout
import android.text.Layout
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min
import kotlin.math.roundToInt

internal data class EditorFont(val id: String, val label: String)
internal object EditorFonts {
    private val families = listOf("OpenSans" to "Open Sans", "NotoSans" to "Noto Sans",
        "NotoSerif" to "Noto Serif", "LiberationSans" to "Liberation Sans",
        "LiberationSerif" to "Liberation Serif", "LiberationMono" to "Liberation Mono",
        "DejaVuSerif" to "DejaVu Serif")
    val all = families.flatMap { (id, label) ->
        listOf(EditorFont("$id-Regular", label), EditorFont("$id-Bold", "$label · жирный"),
            EditorFont("$id-Italic", "$label · курсив"))
    } + listOf(EditorFont("OpenSans-Light", "Open Sans · тонкий"),
        EditorFont("DejaVuSans-ExtraLight", "DejaVu Sans · очень тонкий"))
    private val faces = ConcurrentHashMap<String, Typeface>()
    fun face(context: Context, id: String): Typeface {
        val safe = all.firstOrNull { it.id == id }?.id ?: all.first().id
        return faces.computeIfAbsent(safe) { Typeface.createFromAsset(context.assets, "text-fonts/$it.ttf") }
    }
}

internal object TextLayerRenderer {
    /** Same layout for export, preview and selection bounds. */
    fun layout(context: Context, width: Int, height: Int, layer: TextLayer): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = EditorFonts.face(context, layer.fontId)
            textSize = min(width, height) * layer.size
            color = layer.color
            strokeJoin = Paint.Join.ROUND
        }
        return StaticLayout.Builder.obtain(layer.text, 0, layer.text.length, paint,
            (width * 0.9f).toInt().coerceAtLeast(1))
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    }

    suspend fun videoPreview(context: Context, uri: android.net.Uri, positionMs: Long,
        crop: CropFractions, rotation: Int): Bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val retriever = android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: (positionMs + 1)
            val timeUs = positionMs.coerceIn(0L, (duration - 1).coerceAtLeast(0L)) * 1000L
            val frame = (if (android.os.Build.VERSION.SDK_INT >= 27) retriever.getScaledFrameAtTime(timeUs,
                android.media.MediaMetadataRetriever.OPTION_CLOSEST, 1280, 1280)
                else retriever.getFrameAtTime(timeUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST))
                ?: throw java.io.IOException("Кадр недоступен")
            try { preview(frame, crop, rotation) } finally { frame.recycle() }
        } finally { retriever.release() }
    }

    fun draw(context: Context, canvas: Canvas, width: Int, height: Int,
        layers: List<TextLayer>, positionMs: Long = 0L, includeAll: Boolean = false) {
        layers.filter { includeAll && it.text.isNotBlank() || it.visibleAt(positionMs) }.forEach { layer ->
            val layout = layout(context, width, height, layer)
            val paint = layout.paint
            val saved = canvas.save()
            canvas.translate(width * layer.centerX, height * layer.centerY)
            canvas.rotate(layer.rotation)
            canvas.translate(-layout.width / 2f, -layout.height / 2f)
            if (layer.outline > 0f) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = paint.textSize * layer.outline * 2f
                paint.color = layer.outlineColor
                layout.draw(canvas)
            }
            paint.style = Paint.Style.FILL
            paint.color = layer.color
            layout.draw(canvas)
            canvas.restoreToCount(saved)
        }
    }

    /** Small, final-frame preview shared by photo and video text tools. Caller retains source. */
    fun preview(source: Bitmap, crop: CropFractions, rotation: Int,
        mirrorHorizontal: Boolean = false, mirrorVertical: Boolean = false): Bitmap {
        val left = (source.width * crop.left).roundToInt().coerceIn(0, source.width - 1)
        val top = (source.height * crop.top).roundToInt().coerceIn(0, source.height - 1)
        val width = (source.width * crop.keptWidth).roundToInt().coerceIn(1, source.width - left)
        val height = (source.height * crop.keptHeight).roundToInt().coerceIn(1, source.height - top)
        val matrix = Matrix().apply {
            postScale(if (mirrorHorizontal) -1f else 1f, if (mirrorVertical) -1f else 1f)
            postRotate(rotation.toFloat())
        }
        val result = Bitmap.createBitmap(source, left, top, width, height, matrix, true)
        return if (result === source) source.copy(Bitmap.Config.ARGB_8888, false) else result
    }
}
