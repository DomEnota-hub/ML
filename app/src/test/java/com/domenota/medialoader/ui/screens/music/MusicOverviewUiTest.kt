package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.playback.ListeningPeriod
import com.domenota.medialoader.ui.screens.MusicModeScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h1000dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MusicOverviewUiTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()

    @Test fun sharedCardsUseCompactGeometryAndKeepPeriodSeparate(){
        var opened=0
        compose.setContent{MaterialTheme{Column{
            ResumeMusicCard(2){opened++}
            RecentMusicCard{opened++}
            FrequentListeningCard(7,ListeningPeriod.MONTH){opened++}
            SmartMusicCard(SmartMusicPage.MIX){opened++}
        }}}
        val resume=compose.onNodeWithTag("resume-music-card").fetchSemanticsNode().boundsInRoot
        val recent=compose.onNodeWithTag("recent-music-card").fetchSemanticsNode().boundsInRoot
        val mix=compose.onNodeWithTag("smart-card-MIX").fetchSemanticsNode().boundsInRoot
        assertEquals(resume.width,recent.width,.01f)
        assertEquals(resume.height,recent.height,.01f)
        assertEquals(resume.height,mix.height,.01f)
        compose.onNodeWithText("7 треков",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("30 дней",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Автоподборка",substring=true).assertDoesNotExist()
        compose.onNodeWithText("История добавления по датам",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Любимое и новые открытия",useUnmergedTree=true).assertDoesNotExist()
        compose.onNodeWithText("Сохранено сеансов: 2",useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("frequent-playlist-card").performClick()
        compose.runOnIdle{assertEquals(1,opened)}
    }

    @Test fun actualMusicOverviewPlacesCollectionsJustBeforeStatistics(){
        var opened=0
        compose.setContent{MaterialTheme{Box(Modifier.height(280.dp)){
            MusicModeScreen(emptyList(),emptyList(),emptyList(),{},false,null,{_,_->},{},
                {"new"},{_,_->},{},{_,_->},{_,_->},{_,_,_->},{},{},
                searchVisible=false,tabOverride=1,onPlayFrequent={_,_->},onSaveFrequent={_,_->},onResumeSelection={},onOpenCollections={opened++})
        }}}
        val tags=listOf("resume-music-card","recent-music-card","frequent-playlist-card","smart-card-FORGOTTEN","smart-card-MIX","music-collections")
        val list=compose.onNodeWithTag("music-library")
        // Item zero is the real controls header. Each following card must occupy
        // its exact index, not merely exist somewhere in the lazy viewport.
        tags.forEachIndexed{index,tag->
            list.performScrollToIndex(index+1)
            val top=list.fetchSemanticsNode().boundsInRoot.top
            val card=compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue("$tag must be the first card at index ${index+1}",kotlin.math.abs(card.top-top)<20f)
        }
        val collections=compose.onNodeWithTag("music-collections").fetchSemanticsNode().boundsInRoot
        val stats=compose.onNodeWithTag("smart-card-STATS").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(stats.top>=collections.bottom&&stats.top-collections.bottom<24f)
        compose.onNodeWithTag("music-collections").performClick()
        compose.runOnIdle{assertEquals(1,opened)}
    }
    @Test fun actualFolderShuffleMatchesQueueAndRestoreDoesNotRestartPlayback(){
        val key="external:Music/TestFolder/"
        fun row(id:String)=com.domenota.medialoader.core.database.DownloadEntity(
            id,"device","$id.mp3",com.domenota.medialoader.core.model.MediaType.AUDIO,
            com.domenota.medialoader.core.model.DownloadState.COMPLETED,
            "content://media/external/audio/media/${id.first().code}",1024,1,groupId=key)
        val rows=listOf("a","b","c").map(::row)
        val requests=mutableListOf<List<String>>()
        var accepted=false
        compose.activity.getSharedPreferences("music_folder_visibility",0).edit().clear().commit()
        compose.setContent{MaterialTheme{
            MusicModeScreen(rows,rows,emptyList(),{},false,null,{_,_->},{},
                {"new"},{_,_->},{},{_,_->},{_,_->},{_,_,_->},{},{},
                searchVisible=false,tabOverride=1,onFolderPlayExact={ids->requests+=ids;accepted})
        }}
        fun openFolder(){
            compose.onNodeWithTag("music-library").performScrollToNode(hasText("TestFolder"))
            compose.onNodeWithText("TestFolder").performClick()
        }
        fun visibleOrder()=listOf("a","b","c").sortedBy{
            compose.onNodeWithTag("music-track-$it").fetchSemanticsNode().boundsInRoot.top
        }
        openFolder()
        val original=visibleOrder()
        compose.onNodeWithText("Перемешать").performClick()
        compose.waitForIdle()
        assertEquals(original,visibleOrder())
        compose.onNodeWithContentDescription("Восстановить исходный порядок").assertDoesNotExist()
        compose.runOnIdle{accepted=true}
        compose.onNodeWithText("Перемешать").performClick()
        compose.waitForIdle()
        val first=visibleOrder()
        assertEquals(requests.last(),first)
        assertNotEquals(original,first)
        compose.onNodeWithContentDescription("Назад").performClick()
        openFolder()
        assertEquals(first,visibleOrder())
        assertEquals(2,requests.size)
        compose.onNodeWithText("Перемешать").performClick()
        compose.waitForIdle()
        assertEquals(requests.last(),visibleOrder())
        assertNotEquals(first,visibleOrder())
        compose.onNodeWithContentDescription("Восстановить исходный порядок").performClick()
        assertEquals(original,visibleOrder())
        assertEquals(3,requests.size)
    }

    @Test fun duplicateShortcutOnlyAppearsInOrdinaryTrackSearch(){
        val tab = androidx.compose.runtime.mutableIntStateOf(1)
        var opened = 0
        compose.setContent { MaterialTheme {
            MusicModeScreen(emptyList(),emptyList(),emptyList(),{},false,null,{_,_->},{},
                {"new"},{_,_->},{},{_,_->},{_,_->},{_,_,_->},{},{},
                searchVisible=true,tabOverride=tab.intValue,onOpenDuplicates={opened++},onTrashDuplicates={})
        } }
        compose.onNodeWithText("Поиск музыки").assertExists()
        compose.onNodeWithTag("duplicate-search-shortcut").assertDoesNotExist()
        compose.runOnIdle { tab.intValue = 0 }
        compose.onNodeWithTag("duplicate-search-shortcut").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened); tab.intValue = 1 }
        compose.onNodeWithTag("duplicate-search-shortcut").assertDoesNotExist()
        compose.onNodeWithText("Поиск музыки").assertExists()
    }
    @Test fun largerFontGrowsCardInsteadOfClippingThePeriod(){
        compose.setContent{MaterialTheme{
            val density=LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density,1.6f)){
                Box(Modifier.width(320.dp)){FrequentListeningCard(163,ListeningPeriod.MONTH){}}
            }
        }}
        val card=compose.onNodeWithTag("frequent-playlist-card").fetchSemanticsNode().boundsInRoot
        val count=compose.onNodeWithText("163 трека",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val period=compose.onNodeWithText("30 дней",useUnmergedTree=true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(count.top<period.top&&period.bottom<=card.bottom)
        assertTrue(count.left>=card.left&&period.right<=card.right)
    }

    @Test fun unconfirmedRankingIsNotDisplayedAsZeroTracks(){
        val count=androidx.compose.runtime.mutableStateOf<Int?>(null)
        compose.setContent{MaterialTheme{FrequentListeningCard(count.value,ListeningPeriod.MONTH){}}}
        compose.onNodeWithText("Подготовка…",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("0 треков",useUnmergedTree=true).assertDoesNotExist()
        compose.runOnIdle{count.value=8}
        compose.onNodeWithText("8 треков",useUnmergedTree=true).assertExists()
        compose.runOnIdle{count.value=0}
        compose.onNodeWithText("0 треков",useUnmergedTree=true).assertExists()
    }
}
