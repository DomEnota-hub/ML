package com.domenota.medialoader.data.editor

/** Frame-based seeking for constant-frame-rate videos with readable metadata.
 * Variable-frame-rate sources require frame PTS indexing (a later improvement).
 */
internal object VideoTimelineFrameMath {
    fun snapSource(clip: VideoClipDraft, requestedSourceMs: Long,
        frameIntervalMs: Long?): Long {
        val requested = requestedSourceMs.coerceIn(clip.inMs, clip.outMs - 1)
        val interval = frameIntervalMs?.takeIf { it > 0L } ?: return requested
        val rounded = ((requested + interval / 2) / interval) * interval
        return rounded.coerceIn(clip.inMs, clip.outMs - 1)
    }

    fun projectPosition(project: VideoProjectDraft, absoluteMs: Long,
        frameIntervalMs: Long?): Long {
        val at = project.locateAt(absoluteMs)
        val clip = project.clips[at.clipIndex]
        val snapped = snapSource(clip, at.absoluteSourceMs, frameIntervalMs)
        return project.timelineStart(at.clipIndex) + clip.outputAt(snapped)
    }

    fun stepSelected(project: VideoProjectDraft, selectedId: String,
        positionMs: Long, direction: Int, frameIntervalMs: Long): Long {
        require(direction == -1 || direction == 1)
        require(frameIntervalMs > 0L)
        val index = project.indexOf(selectedId)
        require(index >= 0)
        val clip = project.clips[index]
        val local = (positionMs - project.timelineStart(index))
            .coerceIn(0L, clip.durationMs - 1)
        val current = snapSource(clip, clip.sourceAt(local), frameIntervalMs)
        val next = (current + frameIntervalMs * direction)
            .coerceIn(clip.inMs, clip.outMs - 1)
        return project.timelineStart(index) + clip.outputAt(next)
    }
}
