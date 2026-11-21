package com.domenota.medialoader.data.preferences

import android.content.Context
import com.domenota.medialoader.core.model.VideoQualityDirection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { context.getSharedPreferences("ui", 0).edit().clear().commit() }
    @Test fun qualityDirectionMigratesAndPersistsWithoutResettingHeight() {
        val prefs = VideoPreferences(context)
        prefs.preferredHeight = 720
        assertEquals(VideoQualityDirection.LOWER, prefs.qualityDirection)
        prefs.qualityDirection = VideoQualityDirection.HIGHER
        assertEquals(VideoQualityDirection.HIGHER, VideoPreferences(context).qualityDirection)
        assertEquals(720, VideoPreferences(context).preferredHeight)
        context.getSharedPreferences("ui", 0).edit().putString(VideoPreferences.QUALITY_DIRECTION_KEY, "invalid").commit()
        assertEquals(VideoQualityDirection.LOWER, VideoPreferences(context).qualityDirection)
    }
    @Test fun preferencesPersistAndDefaultToSourceCoverAndMaximumQuality() {
        val prefs = VideoPreferences(context)
        assertEquals(VideoThumbnailMode.SOURCE, prefs.thumbnailMode)
        assertEquals(null, prefs.preferredHeight)
        prefs.thumbnailMode = VideoThumbnailMode.FRAME
        prefs.preferredHeight = 480
        assertEquals(VideoThumbnailMode.FRAME, VideoPreferences(context).thumbnailMode)
        assertEquals(480, VideoPreferences(context).preferredHeight)
        prefs.preferredHeight = null
        assertEquals(null, VideoPreferences(context).preferredHeight)
    }
}
