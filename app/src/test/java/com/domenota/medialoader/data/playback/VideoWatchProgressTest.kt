package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoWatchProgressTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("video_playback_positions", Context.MODE_PRIVATE)
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun existingResumePositionAppearsWithoutOpeningTheVideo() {
        prefs.edit().putLong("old", 25_000L).commit()
        assertEquals(0.25f, VideoWatchProgress.read(prefs, "old", 100_000L), 0.001f)
    }
    @Test fun completionStaysVisibleAfterResumePositionIsCleared() {
        VideoWatchProgress.record(prefs, "done", 100_000L, 100_000L)
        prefs.edit().remove("done").commit()
        assertEquals(1f, VideoWatchProgress.read(prefs, "done", null), 0f)
    }
    @Test fun unknownDurationAndInvalidPositionNeverCreateInvalidProgress() {
        prefs.edit().putLong("old", 25_000L).commit()
        assertEquals(0f, VideoWatchProgress.read(prefs, "old", null), 0f)
        VideoWatchProgress.record(prefs, "old", -10L, 100L)
        assertEquals(0f, VideoWatchProgress.read(prefs, "old", null), 0f)
        VideoWatchProgress.record(prefs, "old", 150L, 100L)
        assertEquals(1f, VideoWatchProgress.read(prefs, "old", null), 0f)
    }
    @Test fun aliasSwitchAndHistoryCleanupShareOneResumeAndWatchPosition() {
        prefs.edit().putLong("phone:video:42", 25_000L).commit()
        VideoWatchProgress.linkAliases(prefs, mapOf("phone:video:42" to "download", "download" to "download"))
        assertEquals(25_000L, VideoWatchProgress.resumePosition(prefs, "download"))
        VideoWatchProgress.saveResume(prefs, "download", 70_000L)
        VideoWatchProgress.record(prefs, "download", 70_000L, 100_000L)
        // The canonical app row is gone, while its physical file and preferences remain.
        VideoWatchProgress.linkAliases(prefs, mapOf("phone:video:42" to "phone:video:42"))
        assertEquals(70_000L, VideoWatchProgress.resumePosition(prefs, "phone:video:42"))
        assertEquals(.7f, VideoWatchProgress.read(prefs, "phone:video:42", null), .001f)
        VideoWatchProgress.saveResume(prefs, "phone:video:42", 35_000L)
        assertEquals(35_000L, VideoWatchProgress.resumePosition(prefs, "download"))
    }
    @Test fun anExistingAppBookmarkSurvivesBecomingPhoneOnlyAndCompletion() {
        prefs.edit().putLong("download", 45_000L).commit()
        VideoWatchProgress.linkAliases(prefs, mapOf("phone:video:8" to "download", "download" to "download"))
        VideoWatchProgress.linkAliases(prefs, mapOf("phone:video:8" to "phone:video:8"))
        assertEquals(45_000L, VideoWatchProgress.resumePosition(prefs, "phone:video:8"))
        VideoWatchProgress.record(prefs, "phone:video:8", 100_000L, 100_000L)
        VideoWatchProgress.saveResume(prefs, "phone:video:8", null)
        assertEquals(0L, VideoWatchProgress.resumePosition(prefs, "download"))
        assertEquals(1f, VideoWatchProgress.read(prefs, "download", null), 0f)
    }
}
