package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class GalleryStage5ViewerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun photoShareGlyphIsSmallerButButtonStillDispatches() {
        var shared = 0
        compose.setContent {
            MaterialTheme { GalleryV10Bottom(true, false, { shared++ }, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("gallery-share-glyph", useUnmergedTree = true).assertWidthIsEqualTo(26.dp)
        compose.onNodeWithContentDescription("Поделиться").assertWidthIsEqualTo(59.dp).performClick()
        compose.runOnIdle { assertEquals(1, shared) }
    }

    @Test fun videoBottomUsesSameSmallerGlyphAndAction() {
        var shared = 0
        compose.setContent {
            MaterialTheme { GalleryV10Bottom(false, false, { shared++ }, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("gallery-share-glyph", useUnmergedTree = true).assertWidthIsEqualTo(26.dp)
        compose.onNodeWithContentDescription("Поделиться").performClick()
        compose.runOnIdle { assertEquals(1, shared) }
    }

    @Test @Config(qualifiers = "w800dp-h390dp-land-xhdpi")
    fun landscapeVideoShareHasFullTargetAndSmallerGlyph() {
        var shared = 0
        compose.setContent {
            MaterialTheme { GalleryVideoHeader("15 ноября", "01:00", true, {}, {}, {},
                { shared++ }, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("gallery-share-glyph", useUnmergedTree = true).assertWidthIsEqualTo(25.dp)
        compose.onNodeWithContentDescription("Поделиться").assertWidthIsEqualTo(48.dp).performClick()
        compose.runOnIdle { assertEquals(1, shared) }
    }

    @Test fun menuIconsAreDistinctAndActionIdsStayUnchanged() {
        val ids = listOf("speed", "aspect", "time")
        val glyphs = ids.map { requireNotNull(galleryVideoMenuGlyph(it)) }
        assertEquals(3, glyphs.distinct().size)
        assertNull(galleryVideoMenuGlyph("open"))
        val labels = galleryV10ActionLabels(false)
        assertTrue(labels.indexOf("Скорость воспроизведения") < labels.indexOf("Пропорции видео"))
        assertTrue(labels.indexOf("Пропорции видео") < labels.indexOf("Перейти ко времени"))
    }
}
