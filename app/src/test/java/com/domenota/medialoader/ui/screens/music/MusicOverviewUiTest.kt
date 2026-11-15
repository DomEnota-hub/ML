package com.domenota.medialoader.ui.screens.music

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
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
        val mix=compose.onNodeWithTag("smart-MIX").fetchSemanticsNode().boundsInRoot
        assertEquals(resume.width,recent.width,.01f)
        assertEquals(resume.height,recent.height,.01f)
        assertEquals(resume.height,mix.height,.01f)
        compose.onNodeWithText("7 треков",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("30 дней",useUnmergedTree=true).assertExists()
        compose.onNodeWithText("Автоподборка",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("frequent-playlist-card").performClick()
        compose.runOnIdle{assertEquals(1,opened)}
    }

    @Test fun actualMusicOverviewPlacesCollectionsJustBeforeStatistics(){
        var opened=0
        compose.setContent{MaterialTheme{
            MusicModeScreen(emptyList(),emptyList(),emptyList(),{},false,null,{_,_->},{},
                {"new"},{_,_->},{},{_,_->},{_,_->},{_,_,_->},{},{},
                tabOverride=1,onPlayFrequent={_,_->},onSaveFrequent={_,_->},onResumeSelection={},onOpenCollections={opened++})
        }}
        val tags=listOf("resume-music-card","recent-music-card","frequent-playlist-card","smart-FORGOTTEN","smart-MIX","music-collections","smart-STATS")
        val positions=tags.map{compose.onNodeWithTag(it).assertIsDisplayed().fetchSemanticsNode().boundsInRoot.top}
        assertTrue(positions.zipWithNext().all{(a,b)->a<b})
        compose.onNodeWithTag("music-collections").performClick()
        compose.runOnIdle{assertEquals(1,opened)}
    }
}
