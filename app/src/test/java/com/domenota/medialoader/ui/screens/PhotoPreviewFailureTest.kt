package com.domenota.medialoader.ui.screens

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class PhotoPreviewFailureTest {
    @Test fun cancellationFromRecompositionIsNotAUserVisibleError() {
        assertNull(photoPreviewFailureMessage(
            CancellationException("The coroutine scope left the composition")))
    }

    @Test fun unrelatedRenderingErrorsDoNotMentionWatermark() {
        val error = photoPreviewFailureMessage(IllegalStateException("buffer unavailable"))
        assertNotNull(error)
        assertTrue(error!!.contains("предпросмотр"))
        assertFalse(error.contains("водяной"))
        assertFalse(error.contains("buffer"))
    }
}
