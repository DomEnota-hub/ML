package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.MediaKind
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w393dp-h852dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class UnifiedLibraryScreenTest{
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun favoritesFilterKeepsTypedQueueAndRemovesRowsWhenGateCloses(){
        fun row(id:String,type:MediaType)=DownloadEntity(id,"test",id,type,DownloadState.COMPLETED,null,100L,1L)
        val rows=listOf(row("image-item",MediaType.PHOTO),row("video-item",MediaType.VIDEO),
            row("song-one",MediaType.AUDIO),row("song-two",MediaType.AUDIO))
        var ready by mutableStateOf(true)
        var opened:Pair<String,List<String>>?=null
        compose.setContent{MaterialTheme{
            UnifiedFavoritesScreen(rows,ready,false,true,true,{}, {},{},
                {id,ids->opened=id to ids},{})
        }}
        compose.onNodeWithText("Музыка",substring=false).performScrollTo().performClick()
        compose.onNodeWithTag("favorite-row-image-item").assertDoesNotExist()
        compose.onNodeWithTag("favorite-row-video-item").assertDoesNotExist()
        compose.onNodeWithTag("favorite-row-song-one").performClick()
        compose.runOnIdle{assertEquals("song-one" to listOf("song-one","song-two"),opened)}
        compose.runOnIdle{ready=false}
        compose.onNodeWithTag("favorite-row-song-one").assertDoesNotExist()
    }

    @Test fun trashDeleteConfirmationExcludesNewArrivalsAndOtherTypes(){
        fun row(id:String,kind:MediaKind)=DownloadUiItem(id,id,"Файл",null,state=DownloadUiState.COMPLETED,kind=kind)
        val photo=row("image-item",MediaKind.IMAGE)
        val video=row("video-item",MediaKind.VIDEO)
        val audio=row("song-item",MediaKind.AUDIO)
        var rows by mutableStateOf(listOf(photo,video,audio))
        var removed=emptySet<String>()
        compose.setContent{MaterialTheme{
            TrashScreen(items=rows,onBack={},onOpen={},onRestore={},onDelete={removed=it})
        }}
        compose.onNodeWithText("Видео",substring=false).performScrollTo().performClick()
        compose.onNodeWithText("image-item").assertDoesNotExist()
        compose.onNodeWithText("song-item").assertDoesNotExist()
        compose.onNodeWithText("Выбрать всё",substring=false).performClick()
        compose.onNodeWithText("Удалить",substring=false).performClick()
        compose.runOnIdle{rows=rows+row("new-video",MediaKind.VIDEO)}
        compose.onNodeWithText("Удалить навсегда",substring=false).performClick()
        compose.runOnIdle{assertEquals(setOf("video-item"),removed)}
    }
}
