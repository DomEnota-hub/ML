package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import android.content.Context
import com.domenota.medialoader.data.archive.SimpleArchiveRecord
import com.domenota.medialoader.data.archive.SimpleArchiveGroup
import com.domenota.medialoader.data.archive.SimpleArchiveGroupKind
import com.domenota.medialoader.data.archive.ArchiveEntrySource
import org.junit.Before
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
    @Before fun resetModes(){
        compose.activity.getSharedPreferences("ui",Context.MODE_PRIVATE).edit()
            .remove("archive_grid").remove("favorites_grid").commit()
    }

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

    @Test fun archiveGridKeepsExactFilteredGroupSelectionOnPartialRestore(){
        val group=SimpleArchiveGroup(SimpleArchiveGroupKind.VIRTUAL_FOLDER,"folder","Тест")
        val records=(1..4).map { i ->
            SimpleArchiveRecord("file-$i","content://archive/$i",if(i==4)MediaType.VIDEO else MediaType.PHOTO,
                "Фото $i",ArchiveEntrySource.GALLERY,100L,i.toLong(),group)
        }
        var restored=emptySet<String>()
        compose.setContent{MaterialTheme{
            SimpleArchiveScreen(records,{},{},{restored=it})
        }}
        compose.onNodeWithContentDescription("Показать сеткой").performClick()
        compose.onNodeWithText("Фото",substring=false).performClick()
        compose.onNodeWithText("Тест",substring=false).performClick()
        compose.onNodeWithContentDescription("Выбрать группу").performClick()
        compose.onNodeWithTag("archive-select-file-2",useUnmergedTree=true).performClick()
        compose.onNodeWithTag("archive-grid-file-4").assertDoesNotExist()
        compose.onNodeWithText("Выбрано: 2").assertExists()
        compose.onNodeWithContentDescription("Показать списком").performClick()
        compose.onNodeWithTag("simple-archive-restore").performClick()
        compose.runOnIdle{
            assertEquals(setOf("file-1","file-3"),restored)
            assertFalse(compose.activity.getSharedPreferences("ui",Context.MODE_PRIVATE).getBoolean("archive_grid",true))
        }
    }
    @Test fun favoritesGridHasFourColumnsAndKeepsTypedOpenAndRemove(){
        val rows=(1..5).map { i -> DownloadEntity("song-$i","test","song-$i",MediaType.AUDIO,
            DownloadState.COMPLETED,null,100L,1L) }
        var opened:Pair<String,List<String>>?=null
        var removed:String?=null
        compose.setContent{MaterialTheme{
            UnifiedFavoritesScreen(rows,true,false,true,true,{},{},{},
                {id,ids->opened=id to ids},{removed=it})
        }}
        compose.onNodeWithContentDescription("Показать сеткой").performClick()
        val cells=(1..5).map { compose.onNodeWithTag("favorite-row-song-$it").fetchSemanticsNode().boundsInRoot }
        assertTrue(cells.take(4).all { kotlin.math.abs(it.top-cells[0].top)<2f })
        assertTrue(cells[4].top>cells[0].top)
        compose.onNodeWithTag("favorite-row-song-1").performClick()
        compose.onAllNodesWithContentDescription("Убрать из избранного")[0].performClick()
        compose.runOnIdle{
            assertEquals("song-1" to rows.map{it.id},opened)
            assertEquals("song-1",removed)
            assertTrue(compose.activity.getSharedPreferences("ui",Context.MODE_PRIVATE).getBoolean("favorites_grid",false))
        }
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
        compose.onNodeWithText("Снять выделение",substring=false).performClick()
        compose.onNodeWithText("Выбрано: 0").assertExists()
        compose.onNodeWithText("Выбрать всё",substring=false).performClick()
        compose.onNodeWithText("Удалить",substring=false).performClick()
        compose.runOnIdle{rows=rows+row("new-video",MediaKind.VIDEO)}
        compose.onNodeWithText("Удалить навсегда",substring=false).performClick()
        compose.runOnIdle{assertEquals(setOf("video-item"),removed)}
    }
}
