package com.domenota.medialoader.data.playback

import androidx.work.Constraints

internal object WaveformWorkPolicy {
    // Scheduled work can start before Android enters Doze. The worker yields to
    // active playback/editing through BackgroundMediaWork.awaitIdle().
    fun bulkConstraints() = Constraints.Builder().setRequiresBatteryNotLow(true).build()
    // A user-selected editor track remains available immediately.
    fun priorityConstraints() = Constraints.NONE
}
