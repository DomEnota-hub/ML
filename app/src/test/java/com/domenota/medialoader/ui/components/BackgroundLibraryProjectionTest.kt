package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class BackgroundLibraryProjectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun expensiveWorkIsOffMainAndPrivacyChangeHidesOldSnapshotImmediately() {
        val uiThread = Thread.currentThread()
        var privateKeys by mutableStateOf<Set<String>>(emptySet())
        var shown = ""; var ready = false; var background = false
        val leaks = mutableListOf<Boolean>()
        compose.setContent {
            val state = backgroundLibraryProjection("", privateKeys, "inventory") {
                background = Thread.currentThread() !== uiThread
                if (privateKeys.isEmpty()) "public" else "locked"
            }
            SideEffect {
                shown = state.value; ready = state.ready
                if (privateKeys.isNotEmpty()) leaks.add(state.value == "public")
            }
            Text(state.value)
        }
        compose.waitUntil(5000) { shown == "public" && ready }
        assertTrue(background)
        compose.runOnIdle { privateKeys = setOf("hidden") }
        compose.waitForIdle()
        try { compose.waitUntil(5000) { shown == "locked" && ready } }
        catch (error: Exception) { throw AssertionError("shown=$shown ready=$ready keys=$privateKeys", error) }
        assertFalse(leaks.any { it })
    }
    @Test fun playbackOnlyRecompositionDoesNotRestartProjectionOrLoseReadyEmptyData() {
        var tick by mutableIntStateOf(0)
        val builds = java.util.concurrent.atomic.AtomicInteger()
        val input = emptyList<String>()
        var usable = false
        var ready = false
        compose.setContent {
            val projected = backgroundLibraryProjection(emptyList<String>(), emptySet(), input) {
                builds.incrementAndGet()
                input
            }
            SideEffect { usable = projected.usable; ready = projected.ready }
            Text("${tick}:${projected.value.size}")
        }
        compose.waitUntil(5000) { ready }
        repeat(20) { compose.runOnIdle { tick++ }; compose.waitForIdle() }
        assertEquals(1, builds.get())
        assertTrue(usable)
        assertTrue(ready)
    }


    @Test fun chainedPublicProjectionDoesNotRebuildOnRepeatedModeOrPlaybackChanges() {
        var tick by mutableIntStateOf(0)
        var mode by mutableStateOf("gallery")
        var enabled by mutableStateOf(false)
        val inventory = listOf("a", "b")
        val queue = listOf("c")
        val guard = emptySet<String>()
        val builds = java.util.concurrent.atomic.AtomicInteger()
        val sessions = java.util.concurrent.atomic.AtomicInteger()
        var shown = emptyList<String>()
        var ready = false
        compose.setContent {
            val indexed = backgroundLibraryProjection(emptyList<String>(),guard,inventory) {
                builds.incrementAndGet();inventory.filter { it != "blocked" }
            }
            val session = backgroundLibraryProjection(emptyList<String>(),guard,indexed.value,queue,
                enabled=enabled && indexed.usable) {
                sessions.incrementAndGet();indexed.value + queue
            }
            SideEffect { shown = if (session.usable) session.value else indexed.value; ready = indexed.ready && (!enabled || session.ready) }
            Text("$mode:$tick:${if (session.usable) session.value.size else indexed.value.size}")
        }
        compose.waitUntil(5000) { ready && shown == inventory }
        assertEquals(0,sessions.get())
        compose.runOnIdle { enabled=true }
        compose.waitForIdle()
        compose.waitUntil(5000) { ready && shown==inventory+queue }
        repeat(30) {
            compose.runOnIdle { tick++;mode=if(mode=="gallery") "music" else "gallery" }
            compose.waitForIdle()
        }
        assertEquals(1,builds.get());assertEquals(1,sessions.get())
        assertEquals(inventory+queue,shown)
    }
    @Test fun sameBlockedKeysButDifferentAccessMustHideRetainedSnapshot() {
        var access by mutableStateOf<String?>("before")
        val privacy = emptySet<String>()
        val inventory = listOf("public")
        val builds = java.util.concurrent.atomic.AtomicInteger()
        val leaks=mutableListOf<Boolean>()
        var shown="";var ready=false
        compose.setContent {
            val captured=access
            val result=backgroundLibraryProjection("",privacy,inventory,accessGuard=captured) {
                builds.incrementAndGet();if(captured=="before") "old" else "new"
            }
            SideEffect { shown=result.value;ready=result.ready;if(captured!="before") leaks.add(result.value=="old") }
            Text(result.value)
        }
        compose.waitUntil(5000){shown=="old"&&ready}
        compose.runOnIdle { access=null };compose.waitForIdle()
        assertEquals("",shown);assertFalse(ready);assertEquals(1,builds.get())
        compose.runOnIdle { access="after" }
        compose.waitForIdle()
        compose.waitUntil(5000){shown=="new"&&ready}
        assertEquals(2,builds.get());assertFalse(leaks.any { it })
    }

    @Test fun serialAddressProjectionSeedsBeforeRefreshAndRejectsSupersededGeneration() {
        var revision by mutableIntStateOf(1)
        val firstStarted=java.util.concurrent.atomic.AtomicBoolean(false)
        val release=kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls=java.util.concurrent.CopyOnWriteArrayList<Int>()
        var shown="";var ready=false
        val publications=mutableListOf<String>()
        compose.setContent {
            val captured=revision
            val result=addressedLibraryProjection("", "same-access",captured,serial=true,seed={"saved"}) {
                calls.add(captured)
                if(captured==1) { firstStarted.set(true);release.await() }
                "fresh-$captured"
            }
            SideEffect { shown=result.value;ready=result.ready;publications.add(shown) }
            Text(result.value)
        }
        try {
            compose.waitUntil(5000){firstStarted.get() && shown=="saved"}
            compose.runOnIdle { revision=2 }
            compose.waitForIdle()
            assertEquals("saved",shown);assertEquals(listOf(1),calls.toList())
            compose.runOnIdle { revision=3 }
            compose.waitForIdle()
            release.complete(Unit)
            compose.waitUntil(5000){shown=="fresh-3" && ready}
            assertEquals(listOf(1,3),calls.toList())
            assertFalse(publications.contains("fresh-1"));assertFalse(publications.contains("fresh-2"))
        } finally { release.complete(Unit) }
    }
}
