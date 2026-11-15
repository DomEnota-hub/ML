package com.domenota.medialoader.data.editor

import androidx.media3.common.Player

/** Error recovery is explicit; an AndroidView recomposition must not prepare a failed graph. */
internal object VideoEditorPreviewFailurePolicy {
    fun shouldAutoPrepare(player: Player): Boolean = shouldAutoPrepare(
        player.playbackState, player.mediaItemCount, player.playerError != null)

    fun shouldAutoPrepare(state: Int, itemCount: Int, failed: Boolean): Boolean =
        !failed && state == Player.STATE_IDLE && itemCount > 0

    data class RetryTarget(val timelineMs: Long, val clipIndex: Int, val sourceOffsetMs: Long)

    fun retryTarget(project: VideoProjectDraft, positionMs: Long): RetryTarget {
        val timelineMs = if (positionMs >= (project.durationMs - 40L).coerceAtLeast(0L)) 0L
            else positionMs.coerceIn(0L, project.durationMs - 1L)
        val location = project.locateAt(timelineMs)
        val clip = project.clips[location.clipIndex]
        return RetryTarget(timelineMs, location.clipIndex,
            (clip.sourceAt(location.positionWithinClipMs) - clip.inMs)
                .coerceIn(0L, (clip.sourceSpanMs - 1L).coerceAtLeast(0L)))
    }
}
