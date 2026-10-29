package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.PreviewMediaUi
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class MediaRowTouchTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun textAndRadioSelectWhileOnlyThumbnailPreviews() {
        var selections = 0
        var previews = 0
        val item = PreviewMediaUi("v", "video", "720p · Видео · MP4", "Размер неизвестен", MediaKind.VIDEO, false)
        compose.setContent { MaterialTheme { MediaRow(item, { selections++ }, { previews++ }, singleChoice = true) } }
        compose.onNodeWithText(item.subtitle).performClick()
        assertEquals(1, selections)
        assertEquals(0, previews)
        compose.onNodeWithContentDescription("Предпросмотр: video").performClick()
        assertEquals(1, previews)
        assertEquals(1, selections)
        compose.onNodeWithContentDescription("Выбрать: video").performClick()
        assertEquals(2, selections)
        assertEquals(1, previews)
    }
}
