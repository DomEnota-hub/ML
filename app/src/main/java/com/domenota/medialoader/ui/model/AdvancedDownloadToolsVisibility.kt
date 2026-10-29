package com.domenota.medialoader.ui.model

/** Keeps unfinished bulk download tools behind one deliberate advanced-mode switch. */
object AdvancedDownloadToolsVisibility {
    fun showBulkLinks(advancedToolsVisible: Boolean): Boolean = advancedToolsVisible

    fun showVkCommunity(advancedToolsVisible: Boolean): Boolean = advancedToolsVisible
}
