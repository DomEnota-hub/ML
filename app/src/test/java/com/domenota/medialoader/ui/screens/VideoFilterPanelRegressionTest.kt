package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.editor.*
import org.junit.Assert.*
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
class VideoFilterPanelRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun originalHidesStrengthAndFilterSelectionEnablesRealSlider() {
        val grade = mutableStateOf(VideoColorGrade())
        compose.setContent {
            MaterialTheme { VideoColorPanel(grade.value, true, { grade.value = it }) }
        }
        compose.onNodeWithTag("video-filter-intensity").assertDoesNotExist()
        // Click an actual visible preset, not a synthetic invocation.
        val effects = SharedVideoFilterCatalog.forCategory(PhotoEffectCategory.ALL)
        val other = effects.first { it != PhotoEffect.NONE }
        compose.onNodeWithTag("editor-filter-${other.name}").performClick()
        compose.onNodeWithTag("video-filter-intensity").assertIsEnabled()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
        compose.runOnIdle { assertEquals(other, grade.value.photoEffect); assertEquals(0f, grade.value.intensity, .001f) }
        compose.onNodeWithTag("editor-filter-NONE").performClick()
        compose.onNodeWithTag("video-filter-intensity").assertDoesNotExist()
    }

    @Test fun legacyPresetRetainsStrengthWithoutMislabelingOriginal() {
        val legacy = VideoColorGrade(preset = VideoFilterPreset.CINEMATIC)
        assertTrue(videoFilterHasIntensity(legacy))
        assertFalse(videoFilterSelected(legacy, PhotoEffect.NONE))
        assertFalse(videoFilterHasIntensity(legacy.copy(photoEffect = PhotoEffect.NONE)))
        assertTrue(videoFilterHasIntensity(VideoColorGrade(photoEffect = PhotoEffect.MONO, intensity = 0f)))
    }

    @Test fun frameIdentityAndPositionsFollowActualTrimAndResolution() {
        val clip = VideoClipDraft("one", "content://clip", 10000L, 2000L, 6000L)
        assertEquals(listOf(4000L, 3000L, 5000L), VideoFilterFrame.positions(clip))
        assertNotEquals(VideoFilterFrame.key(clip, 256), VideoFilterFrame.key(clip, 384))
        assertNotEquals(VideoFilterFrame.key(clip, 256), VideoFilterFrame.key(clip.copy(outMs = 7000L), 256))
        assertEquals(VideoFilterFrame.key(clip, 256), VideoFilterFrame.key(clip.copy(color = VideoColorGrade(photoEffect = PhotoEffect.MONO)), 256))
        assertEquals(listOf(2L), VideoFilterFrame.positions(clip.copy(inMs = 2L, outMs = 3L)))
    }

    @Test fun portraitAndLandscapeRetainEntireCompositionAndSmallFramesAreNotUpscaled() {
        for ((w, h) in listOf(720 to 1280, 1280 to 720, 80 to 40)) {
            val source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            source.eraseColor(Color.BLUE)
            source.setPixel(0, 0, Color.RED)
            val fitted = VideoFilterFrame.fitOwned(source, 384)
            try {
                val expected = PhotoFilterThumbnail.dimensions(w, h, 384)
                assertEquals(expected.first, fitted.width)
                assertEquals(expected.second, fitted.height)
                val rendered = PhotoFilterThumbnail.render(fitted, PhotoEffect.NONE, 384)
                try { assertEquals(fitted.width, rendered.width); assertEquals(fitted.height, rendered.height)
                    assertEquals(Color.BLUE, rendered.getPixel(rendered.width / 2, rendered.height / 2)) }
                finally { rendered.recycle() }
                assertTrue(VideoFilterFrame.isInformative(fitted))
                fitted.eraseColor(Color.BLACK)
                assertFalse(VideoFilterFrame.isInformative(fitted))
            } finally { fitted.recycle() }
        }
    }
}
