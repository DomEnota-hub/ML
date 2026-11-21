package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicTrackMultiSelectUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun longPressArtworkStartsCircleSelectionWithoutAccidentalPlayback() {
        val track=DownloadEntity("test-track","local","Song.mp3",
            MediaType.AUDIO,DownloadState.COMPLETED,null,1024,1,
            sourceAudioArtist="Artist")
        var selected by mutableStateOf(false)
        var previews=0
        compose.setContent { MaterialTheme {
            MusicLibraryTrackRow(track, { previews++ },
                selectionMode=selected,selected=selected,
                onArtworkLongPress={ selected=true },
                onToggleSelection={ selected=!selected }) {
                androidx.compose.material3.Text("Actions")
            }
        } }
        compose.onNodeWithTag("music-artwork-test-track").performTouchInput { longClick() }
        compose.onNodeWithTag("music-selection-test-track").assertExists()
        assertEquals(0,previews)
        compose.onNodeWithTag("music-selection-test-track").performClick()
        compose.onNodeWithTag("music-selection-test-track").assertDoesNotExist()
        assertEquals(0,previews)
        compose.onNodeWithTag("music-track-test-track").performClick()
        assertEquals(1,previews)
    }
}
