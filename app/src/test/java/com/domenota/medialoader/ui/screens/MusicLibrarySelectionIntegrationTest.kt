package com.domenota.medialoader.ui.screens

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
class MusicLibrarySelectionIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun selectedAudioGoesToExistingCollectionPickerAndNotToPlayback() {
        val track=DownloadEntity("collection-track","device","Safe.mp3",
            MediaType.AUDIO,DownloadState.COMPLETED,
            "content://media/external/audio/media/314",1024,1,
            sourceAudioTitle="Safe")
        var previews=0
        var collections=emptyList<String>()
        compose.setContent { MaterialTheme {
            MusicModeScreen(
                items=listOf(track),allAudio=listOf(track),playlists=emptyList(),
                header={},loading=false,message=null,
                onOpen={ _,_-> previews++ },onOpenPrivate={},
                onCreate={ "created" },onRename={ _,_-> },
                onDeletePlaylist={},onAddTracks={ _,_-> },
                onRemoveTrack={ _,_-> },onMoveTrack={ _,_,_-> },
                onShare={},onDeleteFile={},searchVisible=false,
                onAddCollection={ collections=it },
            )
        } }
        compose.onNodeWithTag("music-library")
            .performScrollToNode(hasTestTag("music-artwork-collection-track"))
        compose.onNodeWithTag("music-artwork-collection-track")
            .performTouchInput { longClick() }
        compose.onNodeWithTag("music-selection-bottom").assertExists()
        compose.onNodeWithText("Выбрано: 1").assertExists()
        assertEquals(0,previews)
        compose.onNodeWithTag("music-selected-collection").performClick()
        assertEquals(listOf("collection-track"),collections)
        assertEquals(0,previews)
        compose.onNodeWithTag("music-selection-bottom").assertDoesNotExist()
    }
}
