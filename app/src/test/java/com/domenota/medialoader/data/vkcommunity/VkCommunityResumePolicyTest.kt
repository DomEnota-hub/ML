package com.domenota.medialoader.data.vkcommunity

import com.domenota.medialoader.data.storage.SavedFileStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkCommunityResumePolicyTest {
    private val candidate = VkImageCandidate("-7:1", "-7:9", 1L,
        "https://x.test/image.jpg", 100, 100)
    private fun row(phase: VkItemPhase, selected: Boolean = true) =
        VkStoredImage("job", candidate, selected, phase, "content://media/image/1")

    @Test fun onlySelectedMissingCompletedOrDuplicateImagesReturnToQueue() {
        assertTrue(VkCommunityResumePolicy.shouldRequeue(row(VkItemPhase.DONE), SavedFileStatus.MISSING))
        assertTrue(VkCommunityResumePolicy.shouldRequeue(row(VkItemPhase.DUPLICATE), SavedFileStatus.MISSING))
        assertFalse(VkCommunityResumePolicy.shouldRequeue(row(VkItemPhase.DONE), SavedFileStatus.PRESENT))
        assertFalse(VkCommunityResumePolicy.shouldRequeue(row(VkItemPhase.DONE), SavedFileStatus.INACCESSIBLE))
        assertFalse(VkCommunityResumePolicy.shouldRequeue(row(VkItemPhase.DONE, false), SavedFileStatus.MISSING))
        assertFalse(VkCommunityResumePolicy.shouldRequeue(row(VkItemPhase.FOUND), SavedFileStatus.MISSING))
    }
}
