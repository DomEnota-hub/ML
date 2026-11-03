package com.domenota.medialoader.data.playback

import androidx.work.Constraints

internal object WaveformWorkPolicy {
    // A whole-library sweep must not compete with foreground startup/scrolling/editing.
    fun bulkConstraints() = Constraints.Builder().setRequiresDeviceIdle(true)
        .setRequiresBatteryNotLow(true).build()
    // A user-selected editor track remains available immediately.
    fun priorityConstraints() = Constraints.NONE
}
