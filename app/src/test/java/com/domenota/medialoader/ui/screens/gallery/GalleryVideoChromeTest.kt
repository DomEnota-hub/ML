package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryVideoChromeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun clockDisplaysMinutesAndHours() {
        assertEquals("00:00", galleryVideoClock(0L))
        assertEquals("00:19", galleryVideoClock(19_123L))
        assertEquals("01:05", galleryVideoClock(65_000L))
        assertEquals("1:02:03", galleryVideoClock(3_723_000L))
    }
    @Test fun centerControlsAndRightLockAreInSingleGalleryOverlay() {
        var locked by mutableStateOf(false)
        var unlockVisible by mutableStateOf(false)
        var started = false
        compose.setContent {
            MaterialTheme {
                GalleryVideoChrome(
                    visible = true, locked = locked, unlockVisible = unlockVisible,
                    playing = false,
                    positionMs = 4_000, durationMs = 38_000,
                    hasPrevious = true, hasNext = true,
                    onPlayPause = { started = true },
                    onPrevious = {}, onNext = {},
                    onSeekTo = {},
                    onLockChange = { locked = it },
                )
            }
        }
        compose.onNodeWithContentDescription("Воспроизвести").performClick()
        assertTrue(started)
        compose.onNodeWithContentDescription("Заблокировать управление").performClick()
        compose.waitForIdle()
        assertTrue(locked)
        // Clean locked video never keeps the lock icon on screen indefinitely.
        compose.onNodeWithContentDescription("Разблокировать управление").assertDoesNotExist()
        unlockVisible = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Разблокировать управление").performClick()
        compose.waitForIdle()
        assertFalse(locked)
    }
}
