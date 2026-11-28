package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed
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
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Test fun gallerySurfaceNeverInflatesNativeControlsAcrossRepeatedCreation() {
        compose.setContent { MaterialTheme {} }
        compose.runOnIdle {
            repeat(3) {
                val view = com.domenota.medialoader.ui.screens.createLocalVideoPlayerView(compose.activity, true)
                assertFalse(view.useController)
                org.junit.Assert.assertNull(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller))
                org.junit.Assert.assertNull(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller_placeholder))
                org.junit.Assert.assertNull(view.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_progress))
                org.junit.Assert.assertNotNull(view.videoSurfaceView)
                org.junit.Assert.assertNotNull(view.overlayFrameLayout)
                org.junit.Assert.assertNotNull(view.subtitleView)
                view.hideController()
                assertFalse(view.isControllerFullyVisible)
            }
            val standalone = com.domenota.medialoader.ui.screens.createLocalVideoPlayerView(compose.activity, false)
            org.junit.Assert.assertNotNull(standalone.findViewById<android.view.View>(androidx.media3.ui.R.id.exo_controller))
            assertTrue(standalone.useController)
        }
    }

    @Test fun missingDurationNeverPretendsResumedVideoIsAtEnd() {
        var duration by mutableStateOf(0L)
        var soughtTo:Long? = null
        compose.setContent {
            MaterialTheme {
                GalleryVideoChrome(
                    visible = true, locked = false, unlockVisible = false,
                    playing = false, positionMs = 86_000L, durationMs = duration,
                    hasPrevious = false, hasNext = false,
                    onPlayPause = {}, onPrevious = {}, onNext = {},
                    onSeekTo = { soughtTo = it }, onLockChange = {},
                )
            }
        }
        compose.onNodeWithText("01:26").assertIsDisplayed()
        compose.onNodeWithText("--:--").assertIsDisplayed()
        org.junit.Assert.assertNull(soughtTo)
        compose.runOnIdle { duration = 147_000L }
        compose.onNodeWithText("02:27").assertIsDisplayed()
        org.junit.Assert.assertNull(soughtTo)
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
                    onLockChange = { locked = it; unlockVisible = it },
                )
            }
        }
        compose.onNodeWithContentDescription("Воспроизвести").performClick()
        assertTrue(started)
        compose.onNodeWithContentDescription("Заблокировать управление").performClick()
        compose.waitForIdle()
        assertTrue(locked)
        // The real player must expose the unlock target immediately after
        // locking and allow restoring control without leaving the video.
        compose.onNodeWithContentDescription("Разблокировать управление").performClick()
        compose.waitForIdle()
        assertFalse(locked)
    }
    @Test fun canvasSeekHidesCenterControlsButKeepsTimeline() {
        var centerVisible by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                GalleryVideoChrome(visible = true, locked = false, unlockVisible = false,
                    playing = true, positionMs = 39_000L, durationMs = 150_000L,
                    hasPrevious = true, hasNext = true, onPlayPause = {}, onPrevious = {},
                    onNext = {}, onSeekTo = {}, onLockChange = {}, centerControlsVisible = centerVisible)
            }
        }
        compose.onNodeWithContentDescription("Пауза").assertIsDisplayed()
        compose.runOnIdle { centerVisible = false }
        compose.onNodeWithContentDescription("Пауза").assertDoesNotExist()
        compose.onNodeWithContentDescription("К началу или предыдущему видео").assertDoesNotExist()
        compose.onNodeWithContentDescription("Следующее видео").assertDoesNotExist()
        compose.onNodeWithText("00:39").assertIsDisplayed()
        compose.onNodeWithText("02:30").assertIsDisplayed()
    }

}
