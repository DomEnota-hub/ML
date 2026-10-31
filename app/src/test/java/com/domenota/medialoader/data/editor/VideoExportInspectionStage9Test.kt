package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoExportInspectionStage9Test {
    @Test fun invalidVideoIsNeverPublishedAsSuccessfulExport() {
        val cases = listOf(
            listOf(1920, 1080, 0),
            listOf(0, 1080, 4000),
            listOf(1920, 0, 4000),
        )
        for (v in cases) assertThrows(IllegalArgumentException::class.java) {
            VideoExportInspectionPolicy.evaluate(v[0], v[1], v[2].toLong(), true,
                8_000_000, 30f, 4000L, null, 30)
        }
        assertThrows(IllegalArgumentException::class.java) {
            VideoExportInspectionPolicy.evaluate(1920, 1080, 4000, false,
                null, null, null, null, null)
        }
    }

    @Test fun legitimateEncoderFallbackIsWarningNotFalseSuccessOrHardFailure() {
        val result = VideoExportInspectionPolicy.evaluate(
            width = 1280, height = 720, durationMs = 10000, hasVideo = true,
            bitrate = 3_500_000, frameRate = 30f,
            expectedDurationMs = 10000,
            expectedSize = VideoExportDimensions(1920, 1080), expectedFrameRate = 30)
        assertEquals(1, result.warnings.size)
        assertEquals("Кодек изменил выходное разрешение", result.warnings.single())
        assertTrue(result.summary.contains("1280×720"))
        assertTrue(result.summary.contains("30.0 FPS"))
        assertTrue(result.summary.contains("3.5 Мбит/с"))
    }

    @Test fun outputRateAndDurationDeviationsAreFlaggedWithoutLosingFile() {
        val result = VideoExportInspectionPolicy.evaluate(
            640, 480, 5000, true, 4_000_000, 61.5f,
            expectedDurationMs = 3000,
            expectedSize = VideoExportDimensions(720, 1280),
            expectedFrameRate = 30)
        assertTrue(result.warnings.any { it.contains("Длительность") })
        assertTrue(result.warnings.any { it.contains("Соотношение") })
        assertTrue(result.warnings.any { it.contains("FPS") })
    }

    @Test fun absentFpsAndBitrateAreNotInvented() {
        val result = VideoExportInspectionPolicy.evaluate(
            width = 720, height = 1280, durationMs = 3000, hasVideo = true,
            bitrate = null, frameRate = null,
            expectedDurationMs = 3000, expectedSize = VideoExportDimensions(720,1280),
            expectedFrameRate = 60)
        assertTrue(result.warnings.isEmpty())
        assertEquals("720×1280", result.summary)
    }
}
