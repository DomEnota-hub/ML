package com.domenota.medialoader.data.editor

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri

/**
 * Create editable independent audio tracks for the audio-bearing video clips.
 * The video track's original sound must be muted after this operation to avoid
 * doubling during playback/export. No source file is modified.
 */
internal object VideoOriginalAudioDetach {
    data class Result(val project: VideoProjectDraft, val newTrackIds: List<String>)

    fun hasAudio(context: Context, sourceUri: String): Boolean = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(sourceUri), null)
            (0 until extractor.trackCount).any { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
        } finally {
            extractor.release()
        }
    }.getOrDefault(false)

    fun detach(project: VideoProjectDraft, audioClipIds: Set<String>): Result {
        val selected = project.clips.filter { it.id in audioClipIds }
        require(selected.isNotEmpty()) { "В выбранных видеоклипах нет звуковой дорожки" }
        require(selected.all { it.mediaKind == VideoClipMediaKind.VIDEO }) {
            "Нельзя отделить звук от фотографии"
        }
        require(project.audioTracks.size + selected.size <= VideoProjectDraft.MAX_AUDIO_TRACKS) {
            "Достигнут предел количества аудиодорожек"
        }
        val added = selected.map { clip ->
            VideoAudioTrackDraft(
                id = "detached-${clip.id}-${project.audioTracks.size}",
                sourceUri = clip.sourceUri,
                atMs = project.timelineStart(project.indexOf(clip.id)),
                sourceDurationMs = clip.sourceDurationMs,
                inMs = clip.inMs,
                outMs = clip.outMs,
                speed = clip.speed,
            )
        }
        return Result(project.copy(audioTracks = project.audioTracks + added),
            added.map { it.id })
    }
}
