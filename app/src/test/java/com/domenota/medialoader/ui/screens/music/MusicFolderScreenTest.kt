package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicFolderScreenTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun openingFolderDoesNotPlayAndControlsRemainSeparate(){
        val row=DownloadEntity("a","device","Track.mp3",MediaType.AUDIO,DownloadState.COMPLETED,null,100,1,sourceAudioArtist="Artist")
        val requests=mutableListOf<Boolean>();var preview="";var back=0;var hidden by mutableStateOf(false)
        compose.setContent{MaterialTheme{
            MusicFolderScreen("Folder",listOf(row),hidden,"a",true,{back++},{hidden=!hidden},{requests+=it},{preview=it}){}
        }}
        compose.onNodeWithTag("music-folder-detail").assertExists()
        assertTrue(requests.isEmpty())
        compose.onNodeWithText("Artist").assertExists()
        compose.onNodeWithText("Воспроизводится").assertDoesNotExist()
        compose.onNodeWithText("Слушать").performClick()
        compose.onNodeWithText("Перемешать").performClick()
        assertEquals(listOf(false,true),requests)
        compose.onNodeWithTag("music-track-a").performClick();assertEquals("a",preview)
        compose.onNodeWithContentDescription("Настройки папки").performClick()
        compose.onNodeWithText("Скрыть из общего списка").performClick()
        compose.onNodeWithText("Скрыта из общего списка").assertExists()
        compose.onNodeWithContentDescription("Назад").performClick();assertEquals(1,back)
    }
    @Test fun folderArtworkLongPressAddsOnlySelectedTrackToCollection() {
        val row=DownloadEntity("folder-track","device","Song.mp3",MediaType.AUDIO,
            DownloadState.COMPLETED,"content://media/external/audio/media/22",1024,1)
        var played=0
        var chosen=emptyList<String>()
        compose.setContent { MaterialTheme {
            MusicFolderScreen("Folder",listOf(row),false,null,false,{}, {},{}, {played++},
                onAddCollection={chosen=it}) {}
        } }
        compose.onNodeWithTag("music-artwork-folder-track").performTouchInput { longClick() }
        compose.onNodeWithTag("music-folder-selection-footer").assertExists()
        compose.onNodeWithTag("music-folder-selected-collection").performClick()
        assertEquals(listOf("folder-track"),chosen)
        assertEquals(0,played)
    }

    @Test fun restoreOrderOnlyChangesDisplayAndNeverRequestsPlayback() {
        val rows=listOf("a","b","c").map { id -> DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,
            DownloadState.COMPLETED,null,1024,1) }
        var displayed by mutableStateOf(rows.reversed())
        var shuffled by mutableStateOf(true)
        var requests=0
        compose.setContent { MaterialTheme {
            MusicFolderScreen("Folder",displayed,false,"b",true,{}, {},{requests++}, {requests++},
                onRestoreOrder=if(shuffled){{displayed=rows;shuffled=false}}else null) {}
        } }
        compose.onNodeWithContentDescription("Восстановить исходный порядок").performClick()
        compose.onNodeWithContentDescription("Восстановить исходный порядок").assertDoesNotExist()
        assertEquals(rows,displayed)
        assertEquals(0,requests)
        compose.onNodeWithTag("music-track-a").assertExists()
    }

    @Test fun folderMenuAddsAllVisibleTracksWithoutPlayback() {
        val rows=listOf("a","b").map { id -> DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,
            DownloadState.COMPLETED,null,1024,1) }
        var played=0
        var added=emptyList<String>()
        compose.setContent { MaterialTheme {
            MusicFolderScreen("Folder",rows,false,null,false,{}, {},{played++}, {played++},
                onAddAllToPlaylist={added=it}) {}
        } }
        compose.onNodeWithContentDescription("Настройки папки").performClick()
        compose.onNodeWithText("Добавить все треки в плейлист").performClick()
        assertEquals(listOf("a","b"),added)
        assertEquals(0,played)
    }
}
