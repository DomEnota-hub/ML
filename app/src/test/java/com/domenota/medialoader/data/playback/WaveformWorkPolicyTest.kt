package com.domenota.medialoader.data.playback

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WaveformWorkPolicyTest {
    @Test fun librarySweepRequiresIdleButVisibleTrackRequestDoesNot() {
        assertTrue(WaveformWorkPolicy.bulkConstraints().requiresDeviceIdle())
        assertTrue(WaveformWorkPolicy.bulkConstraints().requiresBatteryNotLow())
        assertFalse(WaveformWorkPolicy.priorityConstraints().requiresDeviceIdle())
    }
}
