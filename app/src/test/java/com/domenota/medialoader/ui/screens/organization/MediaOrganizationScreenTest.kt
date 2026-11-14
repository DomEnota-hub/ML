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
    @Test fun usableEmptyCollectionDoesNotShowBackgroundProgressAndPrivacyInvalidationDoes() {
        val usable=androidx.compose.runtime.mutableStateOf(true)
        compose.setContent{MaterialTheme{CollectionsScreen(emptyList(),emptyList(),store,null,false,emptyList(),null,false,{},{_,_->},{},{},{},{},usable=usable.value)}}
        compose.onNodeWithTag("organization-loading").assertDoesNotExist()
        compose.onNodeWithText("Создай коллекцию, чтобы собрать разные медиа и ссылки в одном месте. Файлы не копируются.").assertIsDisplayed()
        compose.runOnIdle{usable.value=false}
        compose.onNodeWithTag("organization-loading").assertIsDisplayed()
    }
    @Test fun addFilesOpensFullScreenPickerWithFixedSelectionActions() {
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),emptyList())
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,"c",false,emptyList(),null,true,{},{_,_->},{},{},{},{})}}
        compose.onNodeWithText("Добавить файлы").performClick()
        compose.onNodeWithTag("collection-media-picker").assertIsDisplayed()
        compose.onNodeWithText("Добавить в Work").assertIsDisplayed()
        compose.onNodeWithText("Добавить (0)").assertIsNotEnabled()
        compose.onNodeWithText("Выбрано: 0").assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Нет доступных материалов").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Добавить файлы").assertIsDisplayed()
    }
    @Test fun pickerKeepsSelectionWhenChangingTypeAndSearch() {
        val photo=DownloadEntity("p","device","Holiday.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://media/p",100,100)
        val audio=photo.copy(id="a",mediaType=MediaType.AUDIO,originalName="Song.mp3",savedUri="content://media/a")
        val refs=listOf(photo,audio).map{LibraryMediaRef(it.id,setOf("file-stat:${it.id}"),it,null)}
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent{MaterialTheme{CollectionMediaPicker("Work",refs,emptySet(),emptyList(),emptySet(),false,{},{_,_->})}}
        compose.onNodeWithTag("picker-PHOTO:p").performClick()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithText("Поиск").performTextInput("Missing")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithText("Сбросить").performClick()
        compose.onNodeWithText("Выбрано: 0").assertIsDisplayed()
    }
    @Test fun singleGalleryCollectionHasOrdinaryCoverAndCreateNeighbor() {
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),emptyList())
        compose.setContent{MaterialTheme{CollectionGalleryStrip(listOf(c),emptyList(),{})}}
        compose.onNodeWithTag("collection-create-card").assertIsDisplayed()
        val card=compose.onNodeWithTag("collection-card-c").fetchSemanticsNode().boundsInRoot
        val create=compose.onNodeWithTag("collection-create-card").fetchSemanticsNode().boundsInRoot
        val cover=compose.onNodeWithTag("collection-cover-c",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot
        assertEquals(.8f,card.width/card.height,.01f)
        assertEquals(.8f,create.width/create.height,.01f)
        assertEquals(card.width,create.width,.01f)
        assertEquals(card.height,create.height,.01f)
        assertEquals(card.width,cover.width,.01f)
        assertEquals(card.height,cover.height,.01f)
        assertTrue(card.width<390f*2f/2)
        compose.onNodeWithText("Пустая").assertIsDisplayed()
    }
    @Test fun stripCardWidthStaysEqualForOneTwoAndManyCollections() {
        val one=MediaCollection(MediaCollectionEntity("one","One",1),emptyList())
        val count=androidx.compose.runtime.mutableIntStateOf(1)
        val all=listOf(one,one.copy(record=one.record.copy(id="two",name="Two")),one.copy(record=one.record.copy(id="three",name="Three")))
        compose.setContent{MaterialTheme{CollectionGalleryStrip(all.take(count.intValue),emptyList(),{})}}
        val width=compose.onNodeWithTag("collection-card-one").fetchSemanticsNode().boundsInRoot.width
        for(n in listOf(2,3)){
            compose.runOnIdle{count.intValue=n}
            assertEquals(width,compose.onNodeWithTag("collection-card-one").fetchSemanticsNode().boundsInRoot.width,.01f)
            assertEquals(width,compose.onNodeWithTag("collection-card-two").fetchSemanticsNode().boundsInRoot.width,.01f)
            compose.onNodeWithTag("collection-create-card").assertDoesNotExist()
        }
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
