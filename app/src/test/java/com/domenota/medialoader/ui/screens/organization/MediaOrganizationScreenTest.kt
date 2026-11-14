package com.domenota.medialoader.ui.screens.organization

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import com.domenota.medialoader.core.database.*
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MediaOrganizationScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var db:HistoryDatabase
    private lateinit var store:MediaOrganizationRepository
    @Before fun setup(){db=Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),HistoryDatabase::class.java).allowMainThreadQueries().build();store=MediaOrganizationRepository(db)}
    @After fun close(){db.close()}
    @Test fun deletionClearlyStatesThatOriginalsRemain(){
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),emptyList())
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,"c",false,emptyList(),null,true,{},{_,_->},{},{},{},{})}}
        compose.onNodeWithContentDescription("Удалить коллекцию").performClick()
        compose.onNodeWithText("Удалить коллекцию?").assertIsDisplayed()
        compose.onNodeWithText("Будет удалена только подборка «Work». Все файлы, альбомы и плейлисты останутся.").assertIsDisplayed()
        compose.onNodeWithText("Отмена").performClick()
        compose.onNodeWithText("Work").assertIsDisplayed()
    }
    @Test fun missingPassportDoesNotDisplayOldPrivateMetadata(){
        compose.setContent{MaterialTheme{MediaPassportScreen(null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),{},{_,_->},{},{})}}
        compose.onNodeWithText("Файл недоступен").assertIsDisplayed()
    }
    @Test fun emptyReviewDoesNotInventExistingDownloadHistory(){
        compose.setContent{MaterialTheme{ReviewScreen(emptyList(),emptyList(),emptyList(),emptySet(),store,true,{},{_,_->},{},{},{},{},{_,_->},{_,_->},{})}}
        compose.onNodeWithText("Всё разобрано. Новые успешные загрузки появятся здесь.").assertIsDisplayed()
    }
}
