package com.domenota.medialoader.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HitMozSelectionGuardTest {
    private fun eligible(
        received: String = "https://ru.hitmoz.org/get/music/track.mp3",
        pendingRevision: Int = 4,
        activeRevision: Int = 4,
        searchActive: Boolean = true,
        loading: Boolean = false,
        visible: Boolean = true,
    ) = HitMozSelectionGuard.accepts(
        pendingUrl = "https://ru.hitmoz.org/get/music/track.mp3",
        callbackUrl = received,
        pendingRevision = pendingRevision,
        activeRevision = activeRevision,
        hasActiveSearch = searchActive,
        loading = loading,
        selectedRowStillVisible = visible,
    )

    @Test fun explicitCurrentChoiceMayProceed() {
        assertTrue(eligible())
    }

    @Test fun staleSearchOrEditedFieldsPreventQueueing() {
        assertFalse(eligible(pendingRevision = 3))
        assertFalse(eligible(searchActive = false))
        assertFalse(eligible(visible = false))
    }

    @Test fun mismatchedOrLateBrowserUrlCannotChooseDifferentTrack() {
        assertFalse(eligible(received = "https://ru.hitmoz.org/get/music/other.mp3"))
        assertFalse(eligible(loading = true))
    }
}
