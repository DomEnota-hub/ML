package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.domenota.medialoader.core.logging.AppLog
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

    internal data class OcrAttempt(val text: String, val confidence: Int, val stage: String) {
        val letterCount: Int get() = text.count { it.isLetter() }
        val longestWord: Int get() = Regex("""[\p{L}]{2,}""").findAll(text)
            .maxOfOrNull { it.value.length } ?: 0
        val usable: Boolean get() = (longestWord >= 3 && confidence >= 32) ||
            (longestWord >= 2 && confidence >= 75)
        val score: Float get() = if (!usable) -1f
            else confidence + longestWord.coerceAtMost(12) * 1.6f +
                (if (text.any { it in 'А'..'я' || it == 'ё' || it == 'Ё' }) 5f else 0f)
    }

    /** Excludes low-confidence OCR noise from photos that contain no text. */
    internal fun bestAttempt(attempts: List<OcrAttempt>): TextReadResult {
        val result = attempts.filter { it.usable }.maxByOrNull { it.score }
        return if (result == null) TextReadResult("", 0) else TextReadResult(result.text, result.confidence)
    }

    /**
     * PSM_AUTO often misses a single decorative caption surrounded by a large photo.
     * Try sparse and block layouts on the full image, then a contrast-boosted version
     * and overlapping vertical bands. No OCR text is written into diagnostics.
     */
    suspend fun readPage(context: Context, image: Bitmap): TextReadResult = withEngine(context) { api ->
        val attempts = mutableListOf<OcrAttempt>()
        fun recognize(bitmap: Bitmap, mode: Int, stage: String): OcrAttempt {
            api.pageSegMode = mode
            api.setImage(bitmap)
            val candidate = OcrAttempt(api.getUTF8Text().orEmpty().trim(), api.meanConfidence(), stage)
            attempts.add(candidate)
            AppLog.i("PhotoOCR", "stage=$stage · width=${bitmap.width} · height=${bitmap.height} · letters=${candidate.letterCount} · confidence=${candidate.confidence}")
            return candidate
        }
        val automatic = recognize(image, TessBaseAPI.PageSegMode.PSM_AUTO, "auto")
        currentCoroutineContext().ensureActive()
        if (automatic.usable && automatic.confidence >= 65) {
            return@withEngine TextReadResult(automatic.text, automatic.confidence)
        }
        recognize(image, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, "sparse")
        currentCoroutineContext().ensureActive()
        if (attempts.any { it.usable && it.confidence >= 65 }) {
            return@withEngine bestAttempt(attempts)
        }
        recognize(image, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, "block")
        currentCoroutineContext().ensureActive()
        if (attempts.none { it.usable && it.confidence >= 78 }) {
            val contrast = enhanceTextForOcr(image)
            try {
                recognize(contrast, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT, "contrast-sparse")
                currentCoroutineContext().ensureActive()
                if (attempts.none { it.usable && it.confidence >= 78 }) {
                    val bandHeight = (contrast.height / 2).coerceAtLeast(1)
                    val bandTops = listOf(0, (contrast.height - bandHeight) / 2, contrast.height - bandHeight).distinct()
                    for ((index, top) in bandTops.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val band = Bitmap.createBitmap(contrast, 0, top, contrast.width, bandHeight)
                        try {
                            recognize(band, TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK, "band-$index")
                        } finally {
                            if (band !== contrast) band.recycle()
                        }
                        if (attempts.any { it.usable && it.confidence >= 78 }) break
                    }
                }
                // Captions and memes usually have one outlined phrase in a narrow
                // horizontal strip; a full-page PSM can treat its letters as artwork.
                if (attempts.none { it.usable && it.confidence >= 78 }) {
                    val lineHeight = (contrast.height * .24f).toInt().coerceAtLeast(1)
                    val fractions = listOf(0.06f, .27f, .49f, .67f, .76f)
                    for ((index, fraction) in fractions.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val top = (contrast.height * fraction).toInt()
                            .coerceIn(0, contrast.height - lineHeight)
                        val strip = Bitmap.createBitmap(contrast, 0, top, contrast.width, lineHeight)
                        try {
                            recognize(strip, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE, "caption-$index")
                        } finally {
                            if (strip !== contrast) strip.recycle()
                        }
                        if (attempts.any { it.usable && it.confidence >= 78 }) break
                    }
                }
            } finally { if (contrast !== image) contrast.recycle() }
        }
        val selected = bestAttempt(attempts)
        AppLog.i("PhotoOCR", "result · found=${selected.text.isNotBlank()} · confidence=${selected.confidence} · passes=${attempts.size}")
        selected
    }

    /**
     * High-contrast second attempt: white background, dark outlines, bounded memory.
     * Keep the original color image for the first three OCR attempts.
     */
    internal fun enhanceTextForOcr(image: Bitmap): Bitmap {
        val maxEdge = maxOf(image.width, image.height)
        val scale = (2048f / maxEdge).coerceAtMost(1.5f)
        val width = (image.width * scale).toInt().coerceAtLeast(1)
        val height = (image.height * scale).toInt().coerceAtLeast(1)
        val resized = Bitmap.createScaledBitmap(image, width, height, true)
        try {
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width)
            for (y in 0 until height) {
                resized.getPixels(pixels, 0, width, 0, y, width, 1)
                for (x in pixels.indices) {
                    val p = pixels[x]
                    val a = (p ushr 24) and 255
                    val r = (p ushr 16) and 255
                    val g = (p ushr 8) and 255
                    val b = p and 255
                    val luma = ((r * 299 + g * 587 + b * 114) / 1000 * a + 255 * (255 - a)) / 255
                    pixels[x] = if (luma < 174) Color.BLACK else Color.WHITE
                }
                out.setPixels(pixels, 0, width, 0, y, width, 1)
            }
            return out
        } finally { if (resized !== image) resized.recycle() }
    }

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
