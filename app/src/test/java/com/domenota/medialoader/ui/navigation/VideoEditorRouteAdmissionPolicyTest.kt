package com.domenota.medialoader.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoEditorRouteAdmissionPolicyTest {
    private fun decision(
        row: Boolean = false,
        blocked: Boolean = false,
        ready: Boolean = true,
        elapsed: Long = 0L,
    ) = VideoEditorRouteAdmissionPolicy.decide(
        authorizedVideoAvailable = row,
        explicitlyDenied = blocked,
        publicCatalogReady = ready,
        elapsedMs = elapsed,
    )

    @Test fun transientMissingMediaMustNotPopEditorOnFirstFrame() {
        assertEquals(VideoEditorRouteAdmission.WAIT, decision(elapsed = 0L))
        assertEquals(VideoEditorRouteAdmission.WAIT,
            decision(elapsed = VideoEditorRouteAdmissionPolicy.MIN_WAIT_MS - 1L))
        assertEquals(VideoEditorRouteAdmission.EXIT,
            decision(elapsed = VideoEditorRouteAdmissionPolicy.MIN_WAIT_MS))
    }

    @Test fun rebuildingMediaStoreCanRecoverAfterNormalGracePeriod() {
        assertEquals(VideoEditorRouteAdmission.WAIT, decision(ready = false, elapsed = 21_000L))
        assertEquals(VideoEditorRouteAdmission.OPEN,
            decision(row = true, ready = false, elapsed = 21_001L))
    }

    @Test fun indefinitelyMissingMediaMustNotLeaveAnUnusableEditorForever() {
        assertEquals(VideoEditorRouteAdmission.WAIT,
            decision(ready = false, elapsed = VideoEditorRouteAdmissionPolicy.MAX_WAIT_MS - 1))
        assertEquals(VideoEditorRouteAdmission.EXIT,
            decision(ready = false, elapsed = VideoEditorRouteAdmissionPolicy.MAX_WAIT_MS))
    }

    @Test fun knownBlockedPrivateOrArchivedMediaExitsImmediately() {
        assertEquals(VideoEditorRouteAdmission.EXIT,
            decision(blocked = true, ready = false, elapsed = 0L))
        assertEquals("Even a stale visible row must not override privacy",
            VideoEditorRouteAdmission.EXIT,
            decision(row = true, blocked = true, ready = false, elapsed = 0L))
    }

    @Test fun onlyCurrentlyAuthorizedRowCanOpenPlayer() {
        assertEquals(VideoEditorRouteAdmission.OPEN,
            decision(row = true, ready = true, elapsed = 0L))
        assertEquals(VideoEditorRouteAdmission.WAIT,
            decision(row = false, ready = false, elapsed = 0L))
    }

    @Test fun repeatedEditorRequestCannotStartAnotherNavigation() {
        assertEquals(true, EditorOpenRequestPolicy.canBegin("viewer", false, false))
        assertEquals(false, EditorOpenRequestPolicy.canBegin("viewer", false, true))
        assertEquals(false, EditorOpenRequestPolicy.canBegin("editor", true, false))
        assertEquals(false, EditorOpenRequestPolicy.canBegin(null, false, false))
    }

    @Test fun suspendedEditorRequestMustStillOwnOriginalNavigationEntry() {
        assertEquals(true, EditorOpenRequestPolicy.canCommit("viewer-1", "viewer-1"))
        assertEquals(false, EditorOpenRequestPolicy.canCommit("viewer-1", "viewer-2"))
        assertEquals(false, EditorOpenRequestPolicy.canCommit("viewer-1", "editor"))
        assertEquals(false, EditorOpenRequestPolicy.canCommit(null, null))
    }
}
