package com.domenota.medialoader.ui.screens

/** Supported alternatives, including unselected groups; unknown tracks are not alternatives. */
internal object GalleryAudioTrackPolicy {
    fun availableCount(supportedAudioGroups: List<List<Boolean>>): Int = supportedAudioGroups.sumOf { group -> group.count { it } }
    fun showSelector(supportedCount: Int): Boolean = supportedCount >= 2
}
