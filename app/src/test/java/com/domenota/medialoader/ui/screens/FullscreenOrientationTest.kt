package com.domenota.medialoader.ui.screens

import android.app.Activity
import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FullscreenOrientationTest {
    @Test fun exitFromLandscapeFullscreenRestoresPortraitGallery() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

        applyFullscreen(activity, activity.window.decorView, fullscreen = false, landscapeVideo = true)

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, activity.requestedOrientation)
    }
}
