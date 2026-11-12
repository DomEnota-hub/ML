package com.domenota.medialoader.data.editor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Tesseract's isolated guesses are not text removal requests. Rank complete
 * lines independently so a large title cannot suppress a separate small URL.
 * Pixel-mask verification is still mandatory before any erasing.
 */
internal object OcrLineCandidatePolicy {
    private val punctuation = setOf('.', '/', ':', '-', '_', '@', '#', ',', '!', '?', '\'')
    private fun hasLetters(glyph: DetectedGlyph) = glyph.text.any(Char::isLetterOrDigit)

    private fun duplicate(a: DetectedGlyph, b: DetectedGlyph): Boolean {
        val overlapX = (min(a.right, b.right) - max(a.left, b.left)).coerceAtLeast(0f)
        val overlapY = (min(a.bottom, b.bottom) - max(a.top, b.top)).coerceAtLeast(0f)
        val smallest = min((a.right - a.left) * (a.bottom - a.top),
            (b.right - b.left) * (b.bottom - b.top))
        return smallest > 0f && overlapX * overlapY / smallest >= .65f
    }

    fun credibleLines(candidates: List<DetectedGlyph>): List<List<DetectedGlyph>> {
        val symbols = candidates.filter {
            it.text.isNotEmpty() && it.text.length <= 3 &&
                (hasLetters(it) || it.text.all(punctuation::contains)) &&
                it.confidence.isFinite() && it.confidence >= 53f &&
                it.right > it.left && it.bottom > it.top
        }.sortedWith(compareBy<DetectedGlyph> { (it.top + it.bottom) / 2f }.thenBy { it.left })
        val lines = mutableListOf<MutableList<DetectedGlyph>>()
        for (symbol in symbols) {
            val height = symbol.bottom - symbol.top
            val cy = (symbol.top + symbol.bottom) / 2f
            val line = lines.firstOrNull { group ->
                val sample = group[group.size / 2]
                val refHeight = sample.bottom - sample.top
                val refCy = (sample.top + sample.bottom) / 2f
                abs(cy - refCy) < max(height, refHeight) * .58f
            }
            if (line == null) lines.add(mutableListOf(symbol)) else line.add(symbol)
        }
        return lines.flatMap { group ->
            // OCR may emit multiple overlapping hypotheses for the same symbol.
            // Keep one, favoring an alphanumeric glyph and the stronger evidence.
            val unique = mutableListOf<DetectedGlyph>()
            for (glyph in group.sortedWith(compareByDescending<DetectedGlyph> { hasLetters(it) }
                .thenByDescending { it.confidence })) {
                if (unique.none { duplicate(it, glyph) }) unique.add(glyph)
            }
            val segments = mutableListOf<MutableList<DetectedGlyph>>()
            for (glyph in unique.sortedBy { it.left }) {
                val prev = segments.lastOrNull()?.lastOrNull()
                val allowance = if (prev == null) 0f else
                    max(glyph.bottom - glyph.top, prev.bottom - prev.top) * 2.5f
                if (prev == null || glyph.left - prev.right > allowance) {
                    segments.add(mutableListOf(glyph))
                } else segments.last().add(glyph)
            }
            segments.mapNotNull { line ->
                val letters = line.filter(::hasLetters)
                if (letters.size < 4 || letters.count { it.confidence >= 66f } < 3) return@mapNotNull null
                val left = letters.minOf { it.left }
                val right = letters.maxOf { it.right }
                val top = letters.minOf { it.top }
                val bottom = letters.maxOf { it.bottom }
                val height = bottom - top
                // Very large, strongly recognized headings are not noise.
                val maxHeight = if (letters.count { it.confidence >= 85f } >= 4) .15f else .075f
                if (right - left < .014f || height > maxHeight) return@mapNotNull null
                // Punctuation is accepted only near an independently verified
                // line; this allows the dots and slashes in a watermark URL.
                line.filter { hasLetters(it) ||
                    (it.right >= left - height * .65f && it.left <= right + height * .65f) }
            }
        }.sortedWith(compareBy<List<DetectedGlyph>> { it.minOf { glyph -> glyph.top } }
            .thenBy { it.minOf { glyph -> glyph.left } })
    }

    fun select(candidates: List<DetectedGlyph>): List<DetectedGlyph> =
        credibleLines(candidates).flatten().take(160)
            .mapIndexed { id, glyph -> glyph.copy(id = id) }

    private fun sameLine(a: List<DetectedGlyph>, b: List<DetectedGlyph>): Boolean {
        val al = a.minOf { it.left }; val ar = a.maxOf { it.right }
        val at = a.minOf { it.top }; val ab = a.maxOf { it.bottom }
        val bl = b.minOf { it.left }; val br = b.maxOf { it.right }
        val bt = b.minOf { it.top }; val bb = b.maxOf { it.bottom }
        val x = (min(ar, br) - max(al, bl)).coerceAtLeast(0f)
        val y = (min(ab, bb) - max(at, bt)).coerceAtLeast(0f)
        return x >= min(ar - al, br - bl) * .25f &&
            y >= min(ab - at, bb - bt) * .45f
    }

    /** Merge distinct reliable lines, never blindly merge duplicate boxes from passes. */
    fun best(fullPage: List<DetectedGlyph>, captionPasses: List<List<DetectedGlyph>>,
        fullPageAlternates: List<List<DetectedGlyph>> = emptyList()): List<DetectedGlyph> {
        val full = (listOf(fullPage) + fullPageAlternates).flatMap(::credibleLines).filter { line ->
            val letters = line.filter(::hasLetters)
            // Full-frame guesses need stronger evidence than a dedicated scan.
            (letters.size >= 5 && letters.count { it.confidence >= 75f } >= 4) ||
                (letters.size == 4 && letters.all { it.confidence >= 85f })
        }
        val all = full + captionPasses.flatMap(::credibleLines)
        val ranked = all.sortedWith(compareByDescending<List<DetectedGlyph>> { line ->
            line.count { hasLetters(it) && it.confidence >= 66f }
        }.thenByDescending { it.count(::hasLetters) }
            .thenByDescending { line -> line.map { it.confidence }.average() })
        val accepted = mutableListOf<List<DetectedGlyph>>()
        for (line in ranked) if (accepted.none { sameLine(it, line) }) accepted.add(line)
        return accepted.sortedWith(compareBy<List<DetectedGlyph>> { it.minOf { glyph -> glyph.top } }
            .thenBy { it.minOf { glyph -> glyph.left } })
            .flatten().take(160).mapIndexed { id, glyph -> glyph.copy(id = id) }
    }
}
