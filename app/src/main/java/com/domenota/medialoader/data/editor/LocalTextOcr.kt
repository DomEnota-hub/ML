package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import com.googlecode.tesseract.android.TessBaseAPI
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class TextReadResult(val text: String, val confidence: Int)

/** Both language models ship in the APK; images and text never leave the device. */
internal object LocalTextOcr {
    private val gate = Mutex()
    suspend fun readLine(context: Context, image: Bitmap): TextReadResult = withEngine(context) { api ->
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SINGLE_LINE
        val mask = TextSampleRaster.read(image).tight()
        val scale = minOf(72f / mask.height, 2600f / mask.width, 6f).coerceAtLeast(1f)
        val targetWidth = (mask.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (mask.height * scale).toInt().coerceAtLeast(1)
        val normalized = Bitmap.createBitmap(mask.width, mask.height, Bitmap.Config.ARGB_8888)
        normalized.setPixels(IntArray(mask.bits.size) { if (mask.bits[it]) android.graphics.Color.BLACK else android.graphics.Color.WHITE },
            0, mask.width, 0, 0, mask.width, mask.height)
        val enlarged = Bitmap.createScaledBitmap(normalized, targetWidth, targetHeight, true)
        val padded = Bitmap.createBitmap(targetWidth + 32, targetHeight + 32, Bitmap.Config.ARGB_8888)
        try {
            val canvas = android.graphics.Canvas(padded)
            canvas.drawColor(android.graphics.Color.WHITE)
            canvas.drawBitmap(enlarged, 16f, 16f, null)
            api.setImage(padded)
            TextReadResult(api.getUTF8Text().orEmpty().trim().replace('\n', ' ').take(120), api.meanConfidence())
        } finally {
            padded.recycle()
            if (enlarged !== normalized) enlarged.recycle()
            normalized.recycle()
        }
    }

    suspend fun glyphs(context: Context, image: Bitmap): List<DetectedGlyph> = withEngine(context) { api ->
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT
        api.setImage(image)
        api.getUTF8Text()
        val iterator = api.resultIterator ?: return@withEngine emptyList()
        val level = TessBaseAPI.PageIteratorLevel.RIL_SYMBOL
        try {
            iterator.begin()
            buildList {
                do {
                    currentCoroutineContext().ensureActive()
                    val text = iterator.getUTF8Text(level).orEmpty()
                    val box = iterator.getBoundingRect(level)
                    if (text.isNotBlank() && box != null) {
                        val l = (box.left.toFloat() / image.width).coerceIn(0f, 1f)
                        val t = (box.top.toFloat() / image.height).coerceIn(0f, 1f)
                        val r = (box.right.toFloat() / image.width).coerceIn(0f, 1f)
                        val b = (box.bottom.toFloat() / image.height).coerceIn(0f, 1f)
                        if (l < r && t < b) add(DetectedGlyph(size, text, l, t, r, b))
                    }
                } while (size < 1200 && iterator.next(level))
            }
        } finally { iterator.delete() }
    }

    private suspend fun <T> withEngine(context: Context, block: suspend (TessBaseAPI) -> T): T =
        gate.withLock {
            val folder = withContext(Dispatchers.IO) {
                val root = File(context.filesDir, "local-text-ocr-v1")
                val data = File(root, "tessdata").apply { mkdirs() }
                listOf("rus", "eng").forEach { language ->
                    val model = File(data, "$language.traineddata")
                    if (!model.isFile) {
                        val temporary = File(data, "$language.tmp")
                        try {
                            context.assets.open("ocr/$language.traineddata").use { input ->
                                temporary.outputStream().use { output -> input.copyTo(output) }
                            }
                            check(temporary.renameTo(model))
                        } finally { temporary.delete() }
                    }
                }
                root
            }
            withContext(Dispatchers.Default) {
                val api = TessBaseAPI()
                try {
                    check(api.init(folder.absolutePath, "rus+eng", TessBaseAPI.OEM_LSTM_ONLY)) {
                        "Не удалось открыть языковые модели"
                    }
                    currentCoroutineContext().ensureActive()
                    block(api)
                } finally { api.recycle() }
            }
        }
}
