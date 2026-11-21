package com.domenota.medialoader.ui.screens.music

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.*
import com.domenota.medialoader.ui.components.DuplicateTrashHost
import com.domenota.medialoader.ui.components.DuplicateTrashOperation
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
class DuplicateTrashHostRegressionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun tenSelectedReachTerminalOutcomesAfterSuspendingVerification(){
        runBatch(false)
    }
    @Test fun changedAndProtectedMembersDoNotBlockIndependentTransfers(){
        runBatch(true)
    }
    @Test fun collapsedProgressRemainsAttachedUntilResultAndCanExpand(){
        runBatch(false, initiallyCollapsed = true)
    }
    private fun runBatch(partial:Boolean, initiallyCollapsed:Boolean=false){
        val context=compose.activity
        val dir=File(context.cacheDir,"duplicate-host-test").apply{mkdirs()}
        val bytes=ByteArray(2048){(it%251).toByte()}
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
        val epoch=DuplicatePrivacyGuard.generation(context)
        val tracks=(0..10).map{i->
            val file=File(dir,"$i.mp3").apply{writeBytes(bytes)}
            DuplicateTrack("test-dup-$i","Test",null,file.name,Uri.fromFile(file).toString(),
                file.length(),file.lastModified(),hash,null,3000,192000,"Music",privacyEpoch=epoch)
        }
        val group=DuplicateGroup(tracks,"Test","Same",DuplicateKind.EXACT)
        val selected=tracks.drop(1).map{it.id}.toSet()
        val refs=tracks.associate{t->
            t.id to LibraryMediaRef(t.id,setOf(t.id),
                DownloadEntity(t.id,"device",t.filename,MediaType.AUDIO,DownloadState.COMPLETED,t.uri,t.bytes,1L),t.id)
        }
        if(partial)File(dir,"2.mp3").writeBytes(ByteArray(bytes.size){77})
        val request=DuplicateTrashSelection(listOf(group),selected,selected,emptyMap())
        val operation=DuplicateTrashOperation(request)
        operation.collapsed.value=initiallyCollapsed
        val moved=mutableSetOf<String>()
        val committed=mutableSetOf<String>()
        try{
            compose.setContent {MaterialTheme{
                DuplicateTrashHost(request,refs,if(partial)tracks[1].id else null,
                    onAppTrash={id,uri->
                        delay(25)
                        assertTrue(moved.add(id))
                        assertTrue(File(requireNotNull(Uri.parse(uri).path)).delete())
                    },
                    onTrashed={uris->committed.addAll(uris);Unit},
                    onClose={},retained=operation)
            }}
            if(initiallyCollapsed) compose.onNodeWithTag("duplicateTrashMiniStatus").assertExists()
            compose.waitUntil(30_000){operation.finished.value}
            if(initiallyCollapsed){
                compose.onNodeWithTag("duplicateTrashMiniStatus").assertExists()
                compose.onNodeWithTag("duplicateTrashExpand").performClick()
            }
            val success=if(partial)8 else 10
            compose.onNodeWithTag("duplicateTrashResult").assertExists()
            compose.onNodeWithTag("duplicateTrashSuccessCount").assertTextEquals("$success")
            compose.onNodeWithTag("duplicateTrashFailureCount").assertTextEquals("${10-success}")
            if(partial) {
                compose.onNodeWithTag("duplicateTrashErrorList").assertDoesNotExist()
                compose.onNodeWithTag("duplicateTrashErrorToggle").performClick()
                compose.onNodeWithTag("duplicateTrashErrorList").assertExists()
                compose.onNodeWithTag("duplicateTrashErrorToggle").performClick()
                compose.onNodeWithTag("duplicateTrashErrorList").assertDoesNotExist()
            }
            compose.runOnIdle{
                assertEquals(10,operation.outcomes.size)
                assertEquals(success,moved.size);assertEquals(success,committed.size)
                assertTrue(File(dir,"0.mp3").isFile)
                if(partial){assertTrue(File(dir,"1.mp3").isFile);assertTrue(File(dir,"2.mp3").isFile)}
            }
            val report=runBlocking{MusicDuplicateSessionStore.trashReport(context)}!!
            assertEquals(10,report.rows.size)
            assertEquals(success,report.rows.count{it.success})
        }finally{dir.deleteRecursively();MusicDuplicateSessionStore.clearForPrivacy(context)}
    }
}
