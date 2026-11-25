package com.domenota.medialoader.ui.screens.organization

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.MediaCollectionEntity
import com.domenota.medialoader.core.database.MediaCollectionMemberEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.model.MediaKind
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LastCollectionCoverPresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context=RuntimeEnvironment.getApplication() as Context
    private val id="durable-ui-regression"
    private val ref=LibraryMediaRef("public-photo",setOf("file-stat:synthetic-photo"),
        DownloadEntity("public-photo","test","Synthetic.jpg",MediaType.PHOTO,DownloadState.COMPLETED,
            "content://test-cover/fixture",100L,1000L),"public-photo")
    private val member=MediaCollectionMemberEntity("cover-member",id,MediaOrganizationPolicy.encode(MediaOrganizationPolicy.keys(ref)),null,1L)
    private val collection=MediaCollection(MediaCollectionEntity(id,"Synthetic",1L),listOf(member))
    @After fun clean(){CollectionCoverStore(context).delete(id);LastCollectionCoverStore.clearMemory()}

    @Test fun lastRenderedCoverSurvivesRecreationWithoutSourcePermissionAndPrivacyRevokesItImmediately() {
        val calls=AtomicInteger()
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        val decoder=object:CollectionOriginalCoverDecoder {
            override suspend fun decode(context:Context,uri:String,kind:MediaKind,previewUrl:String?,identity:String):Bitmap {
                calls.incrementAndGet();return bitmap
            }
        }
        val visible=mutableStateOf(true)
        val source=mutableStateOf<LibraryMediaRef?>(ref)
        val blocked=mutableStateOf<Set<String>?>(emptySet())
        val permitted=mutableStateOf(true)
        compose.setContent{MaterialTheme{
            if(visible.value)CollectionCoverImage(collection,source.value,blockedIdentities=emptySet(),
                coverAccess=CollectionCoverAccess(permitted.value,emptySet(),blocked.value),decoder=decoder)
        }}
        compose.waitUntil(12_000){compose.onAllNodesWithTag("collection-last-cover-image").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{assertEquals(1,calls.get());visible.value=false}
        compose.onNodeWithTag("collection-last-cover-image").assertDoesNotExist()
        compose.runOnIdle{
            // Discard all retained bitmaps: the next composition must read the
            // atomic app-private file, with no source available to the decoder.
            LastCollectionCoverStore.clearMemory();source.value=null;permitted.value=false;visible.value=true
        }
        compose.waitUntil(12_000){compose.onAllNodesWithTag("collection-last-cover-image").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{assertEquals(1,calls.get());source.value=ref.copy(item=ref.item.copy(hidden=true))}
        compose.onNodeWithTag("collection-last-cover-image").assertDoesNotExist()
        compose.runOnIdle{source.value=null}
        compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
        compose.runOnIdle{blocked.value=setOf("uri:content://test-cover/fixture")}
        compose.onNodeWithTag("collection-last-cover-image").assertDoesNotExist()
        compose.runOnIdle{blocked.value=null}
        compose.onNodeWithTag("collection-last-cover-image").assertDoesNotExist()
    }
    @Test fun unchangedBackgroundVerificationDoesNotDecodeAgainAndMemberRemovalCannotReuseOldFrame() {
        val calls=AtomicInteger()
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        val decoder=object:CollectionOriginalCoverDecoder {
            override suspend fun decode(context:Context,uri:String,kind:MediaKind,previewUrl:String?,identity:String):Bitmap {
                calls.incrementAndGet();return bitmap
            }
        }
        val revision=mutableStateOf(1L)
        val current=mutableStateOf(collection)
        compose.setContent{MaterialTheme{
            CollectionCoverImage(current.value,ref,coverAccess=CollectionCoverAccess(true,emptySet(),emptySet(),revision.value),decoder=decoder)
        }}
        compose.waitUntil(12_000){compose.onAllNodesWithTag("collection-last-cover-image").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{revision.value=2L}
        compose.waitForIdle()
        compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
        compose.runOnIdle{assertEquals(1,calls.get());current.value=collection.copy(members=emptyList())}
        compose.onNodeWithTag("collection-last-cover-image").assertDoesNotExist()
    }
    @Test fun changingCustomToAutomaticKeepsTheOldImageUntilTheNewImageIsAccepted() {
        val selected=CollectionCoverStore(context)
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO){
            selected.save(id,"content://test-cover/custom",bitmap,CollectionCrop())
        }
        val replacementStarted=java.util.concurrent.atomic.AtomicBoolean(false)
        val releaseReplacement=kotlinx.coroutines.CompletableDeferred<Unit>()
        val decoder=object:CollectionOriginalCoverDecoder {
            override suspend fun decode(context:Context,uri:String,kind:MediaKind,previewUrl:String?,identity:String):Bitmap {
                replacementStarted.set(true)
                releaseReplacement.await()
                return bitmap
            }
        }
        try {
            compose.setContent{MaterialTheme{
                CollectionCoverImage(collection,ref,coverAccess=CollectionCoverAccess(true,emptySet(),emptySet()),decoder=decoder)
            }}
            compose.waitUntil(12_000){compose.onAllNodesWithTag("collection-last-cover-image").fetchSemanticsNodes().isNotEmpty()}
            assertTrue(LastCollectionCoverStore(context).peek(id)!!.metadata.slotToken.startsWith("custom:"))
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO){selected.automatic(id)}
            compose.waitUntil(12_000){replacementStarted.get()}
            // The replacement decoder is suspended, so a visible frame here can
            // only be the accepted old custom image, independent of timing.
            compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
            assertTrue(LastCollectionCoverStore(context).peek(id)!!.metadata.slotToken.startsWith("custom:"))
            releaseReplacement.complete(Unit)
            compose.waitUntil(12_000){LastCollectionCoverStore(context).peek(id)?.metadata?.slotToken?.startsWith("automatic:")==true}
            compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
        }finally{releaseReplacement.complete(Unit)}
    }

    @Test fun changingAutomaticToCustomKeepsAcceptedFrameWhileSnapshotWriterIsBlocked() {
        val bitmap=Bitmap.createBitmap(320,240,Bitmap.Config.ARGB_8888)
        val decoder=object:CollectionOriginalCoverDecoder {
            override suspend fun decode(context:Context,uri:String,kind:MediaKind,previewUrl:String?,identity:String)=bitmap
        }
        compose.setContent{MaterialTheme{
            CollectionCoverImage(collection,ref,coverAccess=CollectionCoverAccess(true,emptySet(),emptySet()),decoder=decoder)
        }}
        compose.waitUntil(12_000){LastCollectionCoverStore(context).peek(id)?.metadata?.slotToken?.startsWith("automatic:")==true}
        compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
        val field=LastCollectionCoverStore::class.java.getDeclaredField("ioLock").apply{isAccessible=true}
        val lock=field.get(null)
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val holder=kotlin.concurrent.thread {
            synchronized(lock){entered.countDown();release.await()}
        }
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO){
                CollectionCoverStore(context).save(id,"content://test-cover/new-custom",bitmap,CollectionCrop())
            }
            compose.waitUntil(12_000){compose.onAllNodesWithTag("collection-cover-custom-slot").fetchSemanticsNodes().isNotEmpty()}
            // The desired custom mode is already composed, but no replacement can
            // be accepted while the actual snapshot IO lock is held.
            compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
            assertTrue(LastCollectionCoverStore(context).peek(id)!!.metadata.slotToken.startsWith("automatic:"))
        } finally { release.countDown();holder.join(5000) }
        compose.waitUntil(12_000){LastCollectionCoverStore(context).peek(id)?.metadata?.slotToken?.startsWith("custom:")==true}
        compose.onNodeWithTag("collection-last-cover-image").assertIsDisplayed()
    }

}
