package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Ranks actual glyph shapes; an empty result means no credible candidate in the library. */
internal object FontSampleMatcher {
    suspend fun match(context: Context, sample: Bitmap, originalText: String): List<EditorFont> =
        withContext(Dispatchers.Default) {
            require(originalText.isNotBlank() && originalText.length <= 120)
            val reference = TextSampleRaster.read(sample).normalized()
            require(reference.bits.count { it } > 10)
            val ranked = EditorFonts.all.map { font ->
                currentCoroutineContext().ensureActive()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    typeface = EditorFonts.face(context, font.id)
                    textSize = 80f
                    color = Color.WHITE
                }
                val bounds = android.graphics.Rect()
                paint.getTextBounds(originalText, 0, originalText.length, bounds)
                val image = Bitmap.createBitmap((paint.measureText(originalText).toInt() + 24).coerceIn(1, 8000),
                    (bounds.height() + 24).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val candidate = try {
                    val canvas = Canvas(image)
                    canvas.drawColor(Color.BLACK)
                    canvas.drawText(originalText, 12f, 12f - bounds.top, paint)
                    TextSampleRaster.read(image).normalized()
                } finally { image.recycle() }
                font to reference.distanceTo(candidate)
            }.sortedBy { it.second }
            if (ranked.first().second > 0.45f) emptyList() else ranked.take(3).map { it.first }
        }
}
