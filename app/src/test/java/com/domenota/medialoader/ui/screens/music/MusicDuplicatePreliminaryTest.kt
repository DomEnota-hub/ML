package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.library.DuplicateGroup
import com.domenota.medialoader.data.library.DuplicateKind
import com.domenota.medialoader.data.library.DuplicateTrack
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
class MusicDuplicatePreliminaryTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun track(id:String)=DuplicateTrack(id,"Песня","Исполнитель","track-$id.mp3","content://audio/$id",100L,1L,"sha",null,120000L,128000L,null)
    private fun group(vararg ids:String)=DuplicateGroup(ids.map(::track),"Песня","Совпадение",DuplicateKind.EXACT)

    @Test fun provisionalCardsExpandWithoutDeletionAndRetainStateOnUpdate(){
        val first=group("a","b")
        val groups=mutableStateOf(listOf(first))
        compose.setContent{MaterialTheme{PreliminaryMusicGroups(groups.value,100,emptyMap())}}
        val card=compose.onNodeWithTag("music-preliminary-${first.key}")
        compose.onNodeWithText("2 файла",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("track-a.mp3").assertDoesNotExist()
        card.performClick()
        compose.onNodeWithText("track-a.mp3").assertExists()
        compose.runOnIdle{groups.value=listOf(first,group("c","d"))}
        compose.onNodeWithText("track-a.mp3").assertExists()
        compose.onNodeWithText("Переместить в корзину").assertDoesNotExist()
        compose.onNodeWithText("Сравнить записи").assertDoesNotExist()
        card.performClick()
        compose.onNodeWithText("track-a.mp3").assertDoesNotExist()
    }

    @Test fun largeGroupHasAccessToAllFilesWithoutEagerRendering(){
        val big=group(*Array(15){"f$it"})
        compose.setContent{MaterialTheme{PreliminaryMusicGroups(listOf(big),1400,emptyMap())}}
        compose.onNodeWithTag("music-preliminary-${big.key}").performClick()
        compose.onNodeWithTag("music-preliminary-groups").performScrollToNode(hasText("Показать все 15 файлов"))
        compose.onNodeWithText("Показать все 15 файлов").performClick()
        compose.onNodeWithTag("music-preliminary-all-files").performScrollToNode(hasText("track-f14.mp3"))
        compose.onNodeWithText("track-f14.mp3").assertIsDisplayed()
    }
    @Test fun cachedArtworkRequiresCurrentPublicMatchingRow(){
        val t=track("a")
        val row=com.domenota.medialoader.core.database.DownloadEntity(t.id,"device",t.filename,
            com.domenota.medialoader.core.model.MediaType.AUDIO,com.domenota.medialoader.core.model.DownloadState.COMPLETED,t.uri,t.bytes,1)
        assertEquals(row,preliminaryArtworkRow(t,mapOf(t.id to row)))
        assertNull(preliminaryArtworkRow(t,mapOf(t.id to row.copy(savedUri="content://other"))))
        assertNull(preliminaryArtworkRow(t,mapOf(t.id to row.copy(sizeBytes=200))))
        assertNull(preliminaryArtworkRow(t,mapOf(t.id to row.copy(originalName="different.mp3"))))
        assertNull(preliminaryArtworkRow(t,mapOf(t.id to row.copy(isPrivate=true))))
        assertNull(preliminaryArtworkRow(t,mapOf(t.id to row.copy(hidden=true))))
    }
}
