package com.domenota.medialoader.ui.screens.gallery

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h900dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VisualDuplicateStoppedScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun prepare():VisualDuplicateCatalog=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val epoch=DuplicatePrivacyGuard.generation(context)
        val files=listOf("a","b").map{VisualFingerprint(it,"content://media/$it",MediaType.PHOTO,100,10,"sha",16,16,null,listOf(1),true)}
        val result=VisualScanResult(listOf(VisualDuplicateGroup(files,VisualMatch.EXACT)),0,epoch)
        VisualDuplicateSessionStore.clearForPrivacy(context)
        assertTrue(VisualDuplicateSessionStore.save(context,VisualDuplicateSession(result,setOf("a"),0,0,123)))
        VisualDuplicateBackgroundScan.start(context,emptyList(),LibrarySource.APP,epoch)
        VisualDuplicateBackgroundScan.finish(context,VisualDuplicateBackgroundScan.state.value.token,null,cancelled=true)
        val refs=files.map{LibraryMediaRef(it.id,setOf(it.id),DownloadEntity(it.id,"device","${it.id}.jpg",MediaType.PHOTO,DownloadState.COMPLETED,it.uri,100,100),null)}
        VisualDuplicateCatalog(refs,refs,LibrarySource.APP)
    }
    private val summary="Найдена 1 группа"
    @Test fun stoppedReportSurvivesIrrelevantRefreshAndScreenReentryButTrashWaitsForFreshCatalog(){
        val catalog=prepare()
        val visible=mutableStateOf(true)
        val reportReady=mutableStateOf(true)
        compose.setContent{MaterialTheme{if(visible.value)VisualDuplicatesScreen(catalog,false,true,{false},{},{_,_->error("Stale delete")},{},reportReady=reportReady.value)}}
        compose.waitUntil(5000){compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Новый поиск").assertIsNotEnabled()
        compose.onNodeWithText("Подробности").assertExists()
        compose.onNodeWithText("Подготовка полного каталога Галереи…").assertDoesNotExist()
        compose.onNodeWithText("В корзину: 1",substring=true).assertIsNotEnabled()
        // An unrelated MediaStore refresh invalidates the projections, not the
        // previously verified report. It stays visible but read-only.
        compose.runOnIdle{reportReady.value=false}
        compose.waitUntil(5000){compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Обновляю каталог.",substring=true).assertExists()
        compose.onNodeWithText("Подробности").assertIsEnabled()
        compose.onNodeWithTag("visual-thumbnail-media-a").assertExists()
        compose.onNodeWithTag("visual-thumbnail-media-b").assertExists()
        compose.onNodeWithText("В корзину: 1",substring=true).assertIsNotEnabled()
        compose.runOnIdle{reportReady.value=true}
        compose.waitUntil(5000){compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Подробности").performClick()
        compose.onNodeWithTag("visual-result-details").assertExists()
        compose.onNodeWithText(summary).assertExists()
        compose.runOnIdle{visible.value=false}
        compose.waitForIdle()
        compose.runOnIdle{visible.value=true}
        compose.waitUntil(5000){compose.onAllNodesWithText(summary).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText(summary).assertExists()
    }
    @Test fun revokedAccessHidesRetainedReportAndDisablesResumeAndResults(){
        val catalog=prepare()
        compose.setContent{MaterialTheme{VisualDuplicatesScreen(catalog,false,false,{false},{},{_,_->error("No access")},{},reportReady=false)}}
        compose.onNodeWithText("Нет доступа к фото и видео телефона").assertExists()
        compose.onNodeWithText("Продолжить").assertIsNotEnabled()
        compose.onNodeWithText("Результаты").assertIsNotEnabled()
        compose.onNodeWithText(summary).assertDoesNotExist()
        compose.onNodeWithText("a.jpg").assertDoesNotExist()
    }
}
