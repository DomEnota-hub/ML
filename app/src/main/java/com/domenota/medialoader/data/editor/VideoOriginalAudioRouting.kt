package com.domenota.medialoader.data.editor

/** Aligned original sound can use the video's decoder and clock, including a shorter audible range. */
internal object VideoOriginalAudioRouting {
    fun tracks(project: VideoProjectDraft): Map<String, VideoAudioTrackDraft> =
        project.clips.mapIndexedNotNull { index, clip ->
            val matches = project.audioTracks.filter { track ->
                track.id.startsWith("detached-${clip.id}-") && track.sourceUri == clip.sourceUri &&
                    track.speed == clip.speed && track.inMs >= clip.inMs && track.outMs <= clip.outMs &&
                    kotlin.math.abs(track.atMs - project.timelineStart(index) -
                        ((track.inMs - clip.inMs) / clip.speed).toLong()) <= 1L
            }
            matches.singleOrNull()?.let { clip.id to it }
        }.toMap()
}
