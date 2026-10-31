package com.domenota.medialoader.data.editor

import androidx.media3.common.MediaItem

/**
 * ExoPlayer updates its playlist asynchronously after clip editing. Until
 * the queued items match the current draft, its reported position belongs
 * to the previous draft and must not replace the editor's chosen playhead.
 */
internal object VideoPreviewQueueSync {
    fun matches(items: List<MediaItem>, project: VideoProjectDraft): Boolean {
        if (items.size != project.clips.size) return false
        return items.indices.all { index ->
            val media = items[index]
            val clip = project.clips[index]
            media.localConfiguration?.uri?.toString() == clip.sourceUri &&
                (if (clip.mediaKind == VideoClipMediaKind.IMAGE)
                    media.mediaId == "image:${clip.id}:${clip.durationMs}"
                else media.clippingConfiguration.startPositionMs == clip.inMs &&
                    media.clippingConfiguration.endPositionMs == clip.outMs)
        }
    }
}
