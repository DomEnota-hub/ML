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

    /**
     * Multi-scale, local-only OCR. A full-image sparse search is supplemented
     * by a high-resolution caption crop: tiny watermark letters at the lower
     * left otherwise look like noise, while faces can yield random symbols.
     * Every box is remapped to the ORIGINAL bitmap's coordinates.
     */
    suspend fun glyphs(context: Context, image: Bitmap): List<DetectedGlyph> = withEngine(context) { api ->
        val level = TessBaseAPI.PageIteratorLevel.RIL_SYMBOL

        fun scan(
            bitmap: Bitmap, mode: Int,
            sourceX: Int, sourceY: Int, sourceWidth: Int, sourceHeight: Int,
        ): List<DetectedGlyph> {
            api.pageSegMode = mode
            api.setImage(bitmap)
            api.getUTF8Text()
            val iterator = api.resultIterator ?: return emptyList()
            try {
                iterator.begin()
                return buildList {
                    do {
                        val text = iterator.getUTF8Text(level).orEmpty().trim()
                        val box = iterator.getBoundingRect(level)
                        val confidence = iterator.confidence(level)
                        if (text.isNotEmpty() && box != null &&
                            confidence.isFinite() && confidence >= 45f) {
                            val left = ((sourceX + box.left.toFloat() / bitmap.width * sourceWidth) /
                                image.width).coerceIn(0f, 1f)
                            val top = ((sourceY + box.top.toFloat() / bitmap.height * sourceHeight) /
                                image.height).coerceIn(0f, 1f)
                            val right = ((sourceX + box.right.toFloat() / bitmap.width * sourceWidth) /
                                image.width).coerceIn(0f, 1f)
                            val bottom = ((sourceY + box.bottom.toFloat() / bitmap.height * sourceHeight) /
                                image.height).coerceIn(0f, 1f)
                            if (left < right && top < bottom)
                                add(DetectedGlyph(size, text, left, top, right, bottom, confidence))
                        }
                    } while (size < 1200 && iterator.next(level))
                }
            } finally {
                iterator.delete()
            }
        }

        val full = scan(image, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
            0, 0, image.width, image.height)
        currentCoroutineContext().ensureActive()

        // A cropped, upscaled caption is cheaper and more legible than
        // multiplying the resolution of a whole megapixel photograph.
        val capW = (image.width * .70f).toInt().coerceIn(1, image.width)
        val top = (image.height * .69f).toInt().coerceIn(0, image.height - 1)
        val capH = image.height - top
        val crop = Bitmap.createBitmap(image, 0, top, capW, capH)
        val scale = minOf(3.0f, 1920f / capW, 960f / capH)
            .coerceAtLeast(1f)
        val enlarged = Bitmap.createScaledBitmap(crop,
            (capW * scale).toInt().coerceAtLeast(1),
            (capH * scale).toInt().coerceAtLeast(1), true)
        try {
            val croppedSparse = scan(enlarged, TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                0, top, capW, capH)
            currentCoroutineContext().ensureActive()
            val croppedLine = scan(enlarged, TessBaseAPI.PageSegMode.PSM_SINGLE_LINE,
                0, top, capW, capH)
            val captionCandidates = mutableListOf(croppedSparse, croppedLine)
            // Tiny pale watermarks can disappear against a pale floor even
            // after enlargement. Try a local high-contrast crop ONLY when
            // both normal caption passes failed the coherent-line test.
            if (captionCandidates.none { OcrLineCandidatePolicy.select(it).size >= 4 }) {
                currentCoroutineContext().ensureActive()
                val contrast = enhanceTextForOcr(enlarged)
                try {
                    captionCandidates += scan(contrast,
                        TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT,
                        0, top, capW, capH)
                } finally {
                    if (contrast !== enlarged) contrast.recycle()
                }
            }
            val candidates = OcrLineCandidatePolicy.best(full, captionCandidates)
            AppLog.i("PhotoOCR", "glyphs_full=${full.size} " +
                "glyphs_caption=${croppedSparse.size} " +
                "glyphs_line=${croppedLine.size} " +
                "caption_passes=${captionCandidates.size} accepted=${candidates.size}")
            candidates
        } finally {
            if (enlarged !== crop) enlarged.recycle()
            if (crop !== image) crop.recycle()
        }
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
