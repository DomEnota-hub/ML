package com.domenota.medialoader.data.editor

import kotlin.math.abs
import kotlin.math.max

/**
 * Tesseract's isolated character guesses are not text removal requests.
 * Keep visually coherent, confident text lines; never auto-select scattered
 * OCR boxes that happen to resemble letters in hair, clothing or a face.
 */
internal object OcrLineCandidatePolicy {
    fun credibleLines(candidates: List<DetectedGlyph>): List<List<DetectedGlyph>> {
        val symbols = candidates.filter {
            it.text.length <= 3 && it.text.any(Char::isLetterOrDigit) &&
                it.confidence.isFinite() && it.confidence >= 53f &&
                it.right > it.left && it.bottom > it.top
        }.sortedWith(compareBy<DetectedGlyph> { (it.top + it.bottom) / 2f }
            .thenBy { it.left })
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
            val byX = group.sortedBy { it.left }
            val segments = mutableListOf<MutableList<DetectedGlyph>>()
            for (glyph in byX) {
                val prev = segments.lastOrNull()?.lastOrNull()
                val allowance = if (prev == null) 0f else
                    max(glyph.bottom - glyph.top, prev.bottom - prev.top) * 2.5f
                if (prev == null || glyph.left - prev.right > allowance) {
                    segments.add(mutableListOf(glyph))
                } else segments.last().add(glyph)
            }
            segments
        }.filter { line ->
            line.size >= 4 &&
                line.count { it.confidence >= 66f } >= 3 &&
                line.maxOf { it.right } - line.minOf { it.left } >= .014f &&
                line.maxOf { it.bottom } - line.minOf { it.top } <= .075f
        }.sortedWith(compareBy<List<DetectedGlyph>> { it.minOf { glyph -> glyph.top } }
            .thenBy { it.minOf { glyph -> glyph.left } })
    }

    fun select(candidates: List<DetectedGlyph>): List<DetectedGlyph> =
        credibleLines(candidates).flatten().take(160)
            .mapIndexed { id, glyph -> glyph.copy(id = id) }

    /**
     * Prefer the targeted lower-left caption scan only if it yields a real
     * cohesive line, otherwise retain filtered full-page candidates.
     */
    fun best(
        fullPage: List<DetectedGlyph>,
        captionPasses: List<List<DetectedGlyph>>,
    ): List<DetectedGlyph> {
        val caption = captionPasses.map(::select).maxWithOrNull(
            compareBy<List<DetectedGlyph>> { it.size }
                .thenBy { it.sumOf { glyph -> glyph.confidence.toDouble() } }
        ).orEmpty()
        if (caption.size >= 4) return caption
        // Full-frame guesses need stronger evidence than a dedicated caption
        // scan. An apparent 4-letter "word" made from facial texture is
        // safer to leave unselected than to damage the photograph.
        val full = select(fullPage)
        return if (full.size >= 5 &&
            full.count { it.confidence >= 75f } >= 4) full else emptyList()
    }
}
