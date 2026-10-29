package com.domenota.medialoader.ui.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsControllerCompat
import com.domenota.medialoader.ui.screens.applyFullscreen
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransparentNavigationTest {
    @Test fun threeButtonScrimIsDisabledAndIconsFollowDarkAndLightBackgrounds() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val window = activity.window
        window.navigationBarColor = Color.LTGRAY
        window.isNavigationBarContrastEnforced = true
        edgeToEdgeWindow(window, dark = true)
        assertEquals(Color.TRANSPARENT, window.navigationBarColor)
        assertEquals(Color.TRANSPARENT, window.navigationBarDividerColor)
        assertFalse(window.isNavigationBarContrastEnforced)
        assertFalse(WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars)
        transparentNavigation(window, dark = false)
        assertTrue(WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars)
        assertFalse(window.isNavigationBarContrastEnforced)
    }
    @Test fun fullscreenVideoExitDoesNotRestoreTheOpaqueNavigationPanel() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val window = activity.window
        for (mode in listOf("light", "dark")) {
            activity.getSharedPreferences("ui", 0).edit().putString("theme", mode).commit()
            applyFullscreen(activity, window.decorView, fullscreen = true, landscapeVideo = true)
            assertFalse(window.isNavigationBarContrastEnforced)
            window.navigationBarColor = Color.LTGRAY // Simulate a previous window override.
            applyFullscreen(activity, window.decorView, fullscreen = false, landscapeVideo = true)
            assertEquals(Color.TRANSPARENT, window.navigationBarColor)
            assertFalse(window.isNavigationBarContrastEnforced)
            assertEquals(mode == "light", WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars)
        }
    }
}
