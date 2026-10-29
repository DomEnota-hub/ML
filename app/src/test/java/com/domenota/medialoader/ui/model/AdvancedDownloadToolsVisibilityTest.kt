package com.domenota.medialoader.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdvancedDownloadToolsVisibilityTest {
    @Test
    fun `both bulk tools follow the hidden advanced-tools switch`() {
        assertFalse(AdvancedDownloadToolsVisibility.showBulkLinks(false))
        assertFalse(AdvancedDownloadToolsVisibility.showVkCommunity(false))

        assertTrue(AdvancedDownloadToolsVisibility.showBulkLinks(true))
        assertTrue(AdvancedDownloadToolsVisibility.showVkCommunity(true))
    }
}
