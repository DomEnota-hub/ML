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
    @Test fun addressedCollectionCanPlayWhileFullCatalogIsStillLoading() {
        val row=DownloadEntity("a","device","Song.mp3",MediaType.AUDIO,DownloadState.COMPLETED,"content://media/external/audio/media/1",100,100)
        val ref=LibraryMediaRef("a",setOf("file-stat:a"),row,null)
        val member=MediaCollectionMemberEntity("m","c",MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)),null,1)
        val pending=MediaCollectionMemberEntity("p","c",MediaOrganizationPolicy.encode(setOf("file-stat:pending")),null,2)
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),listOf(member,pending))
        val scoped=AddressedCollection(listOf(ref),setOf("p"),emptyMap(),checkedMembers=mapOf("m" to member.identityJson,"p" to pending.identityJson),resolved=mapOf("m" to ref))
        var queue:List<String>?=null
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,"c",false,emptyList(),null,false,{},{_,_->},{queue=it},{},{},{},
            scopedDetails=true,detailCatalogId="c",detailCatalog=scoped)}}
        compose.onNodeWithText("Добавить").assertIsEnabled()
        compose.onNodeWithText("Слушать").assertIsEnabled().performClick()
        compose.runOnIdle{assertEquals(listOf("a"),queue)}
        compose.onNodeWithText("1 недоступен").assertDoesNotExist()
    }
    @Test fun deletionClearlyStatesThatOriginalsRemain(){
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),emptyList())
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,"c",false,emptyList(),null,true,{},{_,_->},{},{},{},{})}}
        compose.onNodeWithContentDescription("Меню коллекции").performClick()
        compose.onNodeWithText("Удалить коллекцию").performClick()
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
        compose.onNodeWithText("Добавить").performClick()
        compose.onNodeWithTag("collection-media-picker").assertIsDisplayed()
        compose.onNodeWithText("Добавить в Work").assertIsDisplayed()
        compose.onNodeWithText("Добавить (0)").assertIsNotEnabled()
        compose.onNodeWithText("Выбрано: 0").assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Нет доступных файлов и ссылок").assertIsDisplayed()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.onNodeWithText("Добавить").assertIsDisplayed()
    }
    @Test fun pickerKeepsSelectionWhenChangingTypeAndSearch() {
        val photo=DownloadEntity("p","device","Holiday.jpg",MediaType.PHOTO,DownloadState.COMPLETED,"content://media/p",100,100)
        val audio=photo.copy(id="a",mediaType=MediaType.AUDIO,originalName="Song.mp3",savedUri="content://media/a")
        val refs=listOf(photo,audio).map{LibraryMediaRef(it.id,setOf("file-stat:${it.id}"),it,null)}
        val restoration=androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent{MaterialTheme{CollectionMediaPicker("Work",refs,emptySet(),emptyList(),emptySet(),false,{},{_,_->})}}
        compose.onNodeWithTag("picker-select-PHOTO:p",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithText("Музыка").performClick()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithText("Поиск").performTextInput("Missing")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.onNodeWithText("Сбросить").performClick()
        compose.onNodeWithText("Выбрано: 0").assertIsDisplayed()
    }
    @Test fun automaticCoverRetainsItsLastAuthorizedFrameAfterLazyDisposal() {
        // Verify the *real Compose display behavior*, but supply a deterministic
        // decoded bitmap. Robolectric SDK34 does not implement the full native
        // image decoder; depending on a real JPEG made this test race Compose
        // disposal and fail with CalledFromWrongThread/SlotTable corruption.
        val image=android.graphics.Bitmap.createBitmap(320,240,android.graphics.Bitmap.Config.ARGB_8888)
        val calls=java.util.concurrent.atomic.AtomicInteger()
        val decoder=object:CollectionOriginalCoverDecoder {
            override suspend fun decode(
                context:android.content.Context, uri:String,
                kind:com.domenota.medialoader.ui.model.MediaKind,
                previewUrl:String?, identity:String,
            ):android.graphics.Bitmap? {
                calls.incrementAndGet()
                return image
            }
        }
        val ref=LibraryMediaRef("stable-cover-key",setOf("file-stat:stable-cover"),
            DownloadEntity("stable-cover-id","device","cover.jpg",MediaType.PHOTO,DownloadState.COMPLETED,
                "content://test-collection/public/cover.jpg",320L,1000L),null)
        val visible=androidx.compose.runtime.mutableStateOf(true)
        try {
            CollectionCoverWarmBitmapCache.clear()
            compose.setContent { MaterialTheme {
                if(visible.value) CollectionOriginalCover(ref,decoder=decoder)
            } }
            compose.waitUntil(12_000) {
                compose.onAllNodesWithTag("collection-original-cover-image").fetchSemanticsNodes().isNotEmpty()
            }
            compose.runOnIdle {
                assertTrue("The real image must have been decoded at least once",calls.get()>0)
                assertNotNull("The first image must be held in the warm cache",
                    CollectionCoverWarmBitmapCache.peek(
                        com.domenota.medialoader.data.library.PersistentProjectionCache.digest(
                            listOf(ref.key,ref.item.savedUri,ref.item.mediaType,ref.item.previewUrl,
                                ref.item.sizeBytes,ref.item.createdAtEpochMillis,
                                ref.aliases.sorted().joinToString("\u0000")).joinToString("\u0000"))+":0"
                    ))
                visible.value=false
            }
            compose.onNodeWithTag("collection-original-cover-image").assertDoesNotExist()
            compose.runOnIdle { visible.value=true }
            compose.onNodeWithTag("collection-original-cover-image").assertIsDisplayed()
            compose.onNodeWithTag("collection-original-cover-placeholder").assertDoesNotExist()
            compose.runOnIdle { visible.value=false }
            compose.onNodeWithTag("collection-original-cover-image").assertDoesNotExist()
        } finally {
            // The cached native Bitmap is shared with an active Compose frame.
            // First revoke the frame on the UI thread and allow the recomposer
            // to drain; otherwise Robolectric's native ViewRoot may dispose it
            // from the worker during the next isolated Compose test.
            compose.runOnIdle { visible.value=false }
            compose.waitForIdle()
            CollectionCoverWarmBitmapCache.clear()
            // Never recycle an image while a composition still owns it.
        }
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
        compose.onNodeWithText("Пустая").assertDoesNotExist()
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
    @Test fun galleryCreateAndListAreDifferentActions(){
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),emptyList())
        var create=false
        var listed=false
        compose.setContent{MaterialTheme{CollectionGalleryStrip(listOf(c),emptyList(),{listed=it==null},{create=true})}}
        compose.onNodeWithTag("collection-create-card").performClick()
        compose.runOnIdle{assertTrue(create);assertFalse(listed)}
        compose.onNodeWithText("Все коллекции").performClick()
        compose.runOnIdle{assertTrue(listed)}
    }
    @Test fun directCreateOpensSharedEditorWithoutPersistingOnCancel(){
        var back=false
        compose.setContent{MaterialTheme{CollectionsScreen(emptyList(),emptyList(),store,null,false,emptyList(),null,true,{back=true},{_,_->},{},{},{},{},initialCreate=true)}}
        compose.onNodeWithTag("collection-create-editor").assertIsDisplayed()
        compose.onNodeWithText("Новая коллекция").assertIsDisplayed()
        compose.onNodeWithText("Создать коллекцию").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Назад").performClick()
        compose.runOnIdle{assertTrue(back)}
    }
    @Test fun countsUseRussianPluralForms(){
        val expected=mapOf(0 to "0 файлов",1 to "1 файл",2 to "2 файла",4 to "4 файла",5 to "5 файлов",11 to "11 файлов",14 to "14 файлов",21 to "21 файл",22 to "22 файла")
        expected.forEach{(n,label)->assertEquals(label,com.domenota.medialoader.core.model.RussianCounts.files(n))}
    }
    @Test fun collectionMusicIsCollapsedAndSelectedTrackReceivesWholeQueue(){
        val rows=listOf("a","b").map{DownloadEntity(it,"device","$it.mp3",MediaType.AUDIO,DownloadState.COMPLETED,"content://media/$it",100,100)}
        val refs=rows.map{LibraryMediaRef(it.id,setOf("file-stat:${it.id}"),it,null)}
        val members=refs.map{MediaCollectionMemberEntity("m-${it.key}","c",MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(it)),null,1)}
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),members)
        var played:Pair<String,List<String>>?=null
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),refs,store,"c",false,emptyList(),null,true,{},{_,_->},{},{},{},{},
            onPlayTrack={id,queue->played=id to queue},miniPlayer={androidx.compose.material3.Text("Test player slot")})}}
        compose.waitUntil(5000){compose.onAllNodesWithTag("collection-music-toggle").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("music-track-a").assertDoesNotExist()
        compose.onNodeWithTag("collection-music-toggle").performClick()
        compose.onNodeWithTag("music-track-b").performClick()
        compose.runOnIdle{assertEquals("b" to listOf("a","b"),played)}
        compose.onNodeWithTag("collection-music-toggle").performClick()
        compose.onNodeWithTag("music-track-b").assertDoesNotExist()
        compose.onNodeWithText("Test player slot").assertIsDisplayed()
    }
    @Test fun confirmedCollectionKeepsButtonsCountAndGeometryDuringPartialRefresh(){
        val rows=(1..5).map{n->DownloadEntity("track-$n","device","$n.mp3",MediaType.AUDIO,DownloadState.COMPLETED,"content://media/$n",100,100)}
        val refs=rows.map{LibraryMediaRef(it.id,setOf("file-stat:${it.id}"),it,null)}
        val missing=DownloadEntity("lost","instagram","Lost.mp4",MediaType.VIDEO,DownloadState.COMPLETED,"content://media/lost",100,100)
        val members=refs.map{MediaCollectionMemberEntity("m-${it.key}","c",MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(it)),null,1)}+
            MediaCollectionMemberEntity("missing","c",MediaOrganizationPolicy.encode(setOf("app:lost")),null,1)
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),members)
        val pending=androidx.compose.runtime.mutableStateOf(false)
        val snapshot=CollectionCatalogSnapshot.select(null,refs,true,true,emptySet())!!
        var played:List<String>?=null
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),snapshot.refs,store,"c",false,emptyList(),null,!pending.value,
            {},{_,_->},{played=it},{},{},{},usable=true,identityIndex=snapshot.identityIndex,history=mapOf(missing.id to missing))}}
        compose.onNodeWithText("5 файлов").assertIsDisplayed()
        compose.onNodeWithText("1 недоступен").assertIsDisplayed()
        val top=compose.onNodeWithTag("collection-music-toggle").fetchSemanticsNode().boundsInRoot.top
        repeat(3){
            compose.runOnIdle{pending.value=!pending.value}
            compose.onNodeWithText("Добавить").assertIsEnabled()
            compose.onNodeWithText("Слушать").assertIsEnabled()
            compose.onNodeWithText("5 файлов").assertIsDisplayed()
            assertEquals(top,compose.onNodeWithTag("collection-music-toggle").fetchSemanticsNode().boundsInRoot.top,.01f)
        }
        compose.onNodeWithText("Слушать").performClick()
        compose.runOnIdle{assertEquals(refs.map{it.key},played)}
    }
    @Test fun unavailablePanelShowsRealNameWithoutExtensionAndPrivacyClearsIt(){
        val row=DownloadEntity("lost","instagram","Иллюзия.mp4",MediaType.VIDEO,DownloadState.COMPLETED,"content://media/lost",100,100)
        val member=MediaCollectionMemberEntity("missing","c",MediaOrganizationPolicy.encode(setOf("app:lost")),null,1)
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),listOf(member))
        val blocked=androidx.compose.runtime.mutableStateOf(emptySet<String>())
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,"c",false,emptyList(),null,true,{},{_,_->},{},{},{},{},
            blockedIdentities=blocked.value,history=mapOf(row.id to row),canRecover={true})}}
        compose.onNodeWithTag("collection-missing-chip").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("collection-missing-sheet").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Иллюзия").assertExists()
        compose.onNodeWithText("Иллюзия.mp4").assertDoesNotExist()
        compose.runOnIdle{blocked.value=setOf(row.id)}
        compose.onNodeWithText("Иллюзия").assertDoesNotExist()
        compose.onNodeWithTag("collection-missing-chip").assertDoesNotExist()
    }
    @Test fun missingPanelSelectionSurvivesClosingAndDropsPrivateMembers(){
        val row=DownloadEntity("missing-a","instagram","Song.mp3",MediaType.AUDIO,DownloadState.COMPLETED,"content://media/missing-a",100,100)
        val member=MediaCollectionMemberEntity("m-missing","c",MediaOrganizationPolicy.encode(setOf("app:${row.id}")),null,1)
        val c=MediaCollection(MediaCollectionEntity("c","Work",1),listOf(member))
        val blocked=androidx.compose.runtime.mutableStateOf(emptySet<String>())
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,"c",false,emptyList(),null,true,{},{_,_->},{},{},{},{},
            blockedIdentities=blocked.value,history=mapOf(row.id to row))}}
        compose.onNodeWithContentDescription("Меню коллекции").performClick()
        compose.onNodeWithText("Выбрать файлы и ссылки").performClick()
        compose.onNodeWithTag("collection-missing-chip").performClick()
        compose.waitUntil(5000){compose.onAllNodesWithTag("missing-select-m-missing",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("missing-select-m-missing",useUnmergedTree=true).performClick()
        compose.onNodeWithContentDescription("Закрыть").performClick()
        compose.onNodeWithText("Выбрано: 1").assertIsDisplayed()
        compose.runOnIdle{blocked.value=setOf(row.id)}
        compose.onNodeWithText("Выбрано: 0").assertIsDisplayed()
        compose.onNodeWithText("Убрать").assertIsNotEnabled()
    }
    @Test fun missingFileDisplayNameOnlyRemovesRecognizedSuffix(){
        val row=DownloadEntity("x","device","live.2026.MP3",MediaType.AUDIO,DownloadState.COMPLETED,null,100,100)
        assertEquals("live.2026",missingCollectionDisplayName(row))
        assertEquals("AC/DC — T.N.T.",missingCollectionDisplayName(row.copy(originalName="AC/DC — T.N.T.")))
        assertEquals("track.bin",missingCollectionDisplayName(row.copy(originalName="track.bin")))
    }
    @Test fun collectionOrderMoveAndRussianLinkForms(){
        assertEquals(listOf("b","c","a"),moveCollectionMember(listOf("a","b","c"),"a","c"))
        assertEquals(listOf("a","b"),moveCollectionMember(listOf("a","b"),"a","missing"))
        assertEquals("112 файлов",com.domenota.medialoader.core.model.RussianCounts.files(112))
        assertEquals("21 ссылка",com.domenota.medialoader.core.model.RussianCounts.links(21))
        assertEquals("14 ссылок",com.domenota.medialoader.core.model.RussianCounts.links(14))
    }
    @Test fun missingPassportDoesNotDisplayOldPrivateMetadata(){
        compose.setContent{MaterialTheme{MediaPassportScreen(null,emptyList(),emptyList(),emptyList(),emptyList(),emptyList(),{},{_,_->},{},{})}}
        compose.onNodeWithText("Файл недоступен").assertIsDisplayed()
    }
    @Test fun emptyReviewDoesNotInventExistingDownloadHistory(){
        compose.setContent{MaterialTheme{ReviewScreen(emptyList(),emptyList(),emptyList(),emptySet(),store,true,{},{_,_->},{},{},{},{},{_,_->},{_,_->},{})}}
        compose.onNodeWithText("Всё разобрано. Новые успешные загрузки появятся здесь.").assertIsDisplayed()
    }

    @Test fun unresolvedCollectionStopsLoadingAndOffersRetryWithoutGallery() {
        val member=MediaCollectionMemberEntity("m","c",MediaOrganizationPolicy.encode(setOf("file-stat:unknown")),null,1)
        val collection=MediaCollection(MediaCollectionEntity("c","Test",1),listOf(member))
        val terminal=AddressedCollection.unconfirmed(listOf(member),emptySet())
        var retried=false
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(collection),emptyList(),store,"c",true,emptyList(),null,false,{},{_,_->},{},{},{},{},
            scopedDetails=true,detailCatalogId="c",detailCatalog=terminal,overviewAccessReady=true,detailChecking=false,onRetryDetail={retried=true})}}
        compose.onNodeWithTag("organization-loading").assertDoesNotExist()
        compose.onNodeWithText("Повторить проверку").assertIsDisplayed().performClick()
        compose.runOnIdle{assertTrue(retried)}
        compose.onNodeWithText("1 недоступен").assertDoesNotExist()
    }
    @Test fun collectionOverviewDoesNotShowFullCatalogSpinnerOncePrivacyIsReady() {
        val c=MediaCollection(MediaCollectionEntity("c","Test",1),emptyList())
        var request=com.domenota.medialoader.ui.CollectionCatalogRequest.ALL
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(c),emptyList(),store,null,true,emptyList(),null,false,{},{_,_->},{},{},{},{},
            scopedDetails=true,overviewAccessReady=true,onCatalogRequest={request=it})}}
        compose.onNodeWithTag("organization-loading").assertDoesNotExist()
        compose.onNodeWithText("Test").assertIsDisplayed()
        compose.runOnIdle{assertEquals(com.domenota.medialoader.ui.CollectionCatalogRequest.NONE,request)}
    }
    @Test fun unsettledDetailRevisionsCannotKeepInitialSpinner() {
        val member=MediaCollectionMemberEntity("m","c",MediaOrganizationPolicy.encode(setOf("file-stat:unknown")),null,1)
        val collection=MediaCollection(MediaCollectionEntity("c","Test",1),listOf(member))
        var retried=false
        compose.mainClock.autoAdvance=false
        compose.setContent{MaterialTheme{CollectionsScreen(listOf(collection),emptyList(),store,"c",true,emptyList(),null,false,{},{_,_->},{},{},{},{},
            scopedDetails=true,detailCatalogId="c",detailCatalog=null,overviewAccessReady=true,detailChecking=true,onRetryDetail={retried=true})}}
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("organization-loading").assertExists()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(16_100))
        compose.mainClock.advanceTimeBy(16_100)
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.onNodeWithTag("organization-loading").assertDoesNotExist()
        compose.onNodeWithText("Повторить проверку").assertExists().performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.runOnIdle{assertTrue(retried)}
    }
    @Test fun emptyOverviewIsNotAnnouncedUntilItsOwnDataIsReady() {
        val loaded=androidx.compose.runtime.mutableStateOf(false)
        compose.setContent{MaterialTheme{CollectionsScreen(emptyList(),emptyList(),store,null,false,emptyList(),null,false,{},{_,_->},{},{},{},{},
            scopedDetails=true,overviewAccessReady=loaded.value)}}
        compose.onNodeWithText("Создай коллекцию, чтобы собрать разные медиа и ссылки в одном месте. Файлы не копируются.").assertDoesNotExist()
        compose.onNodeWithTag("organization-loading").assertExists()
        compose.runOnIdle{loaded.value=true}
        compose.onNodeWithText("Создай коллекцию, чтобы собрать разные медиа и ссылки в одном месте. Файлы не копируются.").assertIsDisplayed()
        compose.onNodeWithTag("organization-loading").assertDoesNotExist()
    }
}
