package com.domenota.medialoader.data.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutoDragSelectionPolicyTest {
    private val first = MaskPoint(.15f, .25f)
    private val last = MaskPoint(.78f, .64f)

    @Test fun tapDoesNotStartInference() {
        assertNull(AutoDragSelectionPolicy.releasePrompt(listOf(first), 0f, 14f))
        assertNull(AutoDragSelectionPolicy.releasePrompt(listOf(first, last), 14f, 14f))
        assertNull(AutoDragSelectionPolicy.releasePrompt(listOf(first, last), 10f, 14f))
    }

    @Test fun deliberateDragPromptsAtReleasePoint() {
        assertEquals(last, AutoDragSelectionPolicy.releasePrompt(listOf(first, last), 22f, 14f))
    }

    @Test fun emptyOrNonFiniteTraceNeverStartsInference() {
        assertNull(AutoDragSelectionPolicy.releasePrompt(emptyList(), 20f, 14f))
        assertNull(AutoDragSelectionPolicy.releasePrompt(listOf(first, last), Float.NaN, 14f))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidThresholdIsRejected() {
        AutoDragSelectionPolicy.releasePrompt(listOf(first, last), 20f, 0f)
    }
}
