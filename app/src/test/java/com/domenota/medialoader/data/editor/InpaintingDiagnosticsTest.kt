package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class InpaintingDiagnosticsTest {
    @Test fun distinguishesHugeSilhouetteFromSmallTextAndMeasuresFlatOutput() {
        val large = InpaintingDiagnosticPolicy.analyze(
            IntArray(100) { if (it < 60) 255 else 0 },
            10, 10,
            FloatArray(100) { if (it < 60) 1f else 0f },
            10,
            FloatArray(300) { .42f },
        )
        assertTrue(large.largeArea)
        assertEquals(.6f, large.sourceMaskFraction, .001f)
        assertEquals(.6f, large.modelMaskFraction, .001f)
        assertEquals(0f, large.modelPredictionSpread, .001f)

        val small = InpaintingDiagnosticPolicy.analyze(
            IntArray(100) { if (it < 3) 255 else 0 },
            10, 10,
            FloatArray(100) { if (it < 3) 1f else 0f },
            10,
            FloatArray(300) { (it % 11) / 10f },
        )
        assertFalse(small.largeArea)
        assertTrue(small.modelPredictionSpread > 0f)
    }

    @Test fun rejectsMismatchedDimensionsRatherThanReportingFalseQuality() {
        try {
            InpaintingDiagnosticPolicy.analyze(IntArray(2), 10, 10,
                FloatArray(100), 10, FloatArray(300))
            fail("Must fail closed on invalid coverage")
        } catch (_: IllegalArgumentException) { }
    }
}
