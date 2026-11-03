package com.domenota.medialoader.data.editor

import kotlin.math.abs

/** Avoid repeated correction seeks starving a decoder which has only just caught up. */
internal object VideoAudioSyncDecision {
    fun shouldSeek(explicitJump: Boolean, ready: Boolean, playing: Boolean,
        driftMs: Long, sinceSeekMs: Long): Boolean = explicitJump ||
        (ready && abs(driftMs) > (if (playing) 180L else 20L) && (!playing || sinceSeekMs >= 750L))
}
