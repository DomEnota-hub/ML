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
    @Test fun receiptTransitionsFilterImmediatelyWithoutPublishingEmptyOrRemovedMedia() {
        var revision by mutableIntStateOf(0)
        var access by mutableStateOf<Any?>("permission-1")
        var unknown by mutableStateOf(false)
        val public = listOf("moving", "untouched")
        val release = java.util.concurrent.atomic.AtomicBoolean(false)
        val waiting = java.util.concurrent.atomic.AtomicBoolean(false)
        val frames = mutableListOf<Pair<Int, List<String>>>()
        val baselineFrames = mutableListOf<Pair<Int, List<String>>>()
        val workers = java.util.concurrent.atomic.AtomicInteger(0)
        var shown = emptyList<String>(); var ready = false; var original = emptyList<String>()
        compose.setContent {
            val generation = revision
            val denied = if (generation % 2 == 1) setOf("moving") else emptySet()
            val baseline = backgroundLibraryProjection(emptyList<String>(), denied, generation,
                accessGuard = if (unknown) null else generation) {
                // Let the old implementation finish: record its first EMPTY frame
                // instead of pinning a second Default worker on a two-core JVM.
                public.filterNot { it in denied }
            }
            val result = backgroundLibraryProjection(emptyList<String>(), denied, generation,
                accessGuard = if (unknown) null else generation,
                retentionGuard = if (unknown) null else access,
                revalidateRetained = { rows -> rows.filterNot { it in denied } }) {
                if (generation > 0 && !release.get()) {
                    workers.incrementAndGet()
                    try {
                        waiting.set(true)
                        while (!release.get()) Thread.yield()
                    } finally { workers.decrementAndGet() }
                }
                public.filterNot { it in denied }
            }
            SideEffect { shown=result.value; ready=result.ready; original=baseline.value; frames.add(generation to shown); baselineFrames.add(generation to original) }
            Text(shown.joinToString())
        }
        try {
            compose.waitUntil(5000) { ready && shown == public && original == public }
            compose.runOnIdle { revision=1 }
            compose.waitForIdle()
            try { compose.waitUntil(5000) { waiting.get() && shown==listOf("untouched") } }
            catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
                throw AssertionError("projection evidence revision=$revision waiting=${waiting.get()} workers=${workers.get()} ready=$ready shown=$shown frames=$frames baseline=$baselineFrames", failure)
            }
            assertFalse(ready)
            assertTrue(baselineFrames.any { it.first==1 && it.second.isEmpty() }) // Original first EMPTY reproduced.
            assertTrue(frames.filter { it.first==1 }.all { it.second==listOf("untouched") })
            release.set(true)
            compose.waitUntil(5000) { workers.get()==0 && ready }
            compose.runOnIdle { release.set(false);waiting.set(false);revision=2 }
            compose.waitForIdle()
            compose.waitUntil(5000) { waiting.get() && shown==listOf("untouched") }
            assertEquals(listOf("untouched"),shown) // SHOW must not resurrect an unverified locator.
            compose.runOnIdle { unknown=true }
            compose.waitForIdle();assertTrue(shown.isEmpty())
            release.set(true)
            compose.waitUntil(5000) { workers.get()==0 }
            compose.runOnIdle { release.set(false);unknown=false;access="permission-2" }
            compose.waitForIdle();assertTrue(shown.isEmpty())
        } finally { release.set(true) }
    }

    @Test fun identicalGalleryGenerationsKeepPreviousObjectButRevokeOnAccessChange() {
        data class PreparedGrid(val ids:List<String>,val sourceGeneration:Int)
        var generation by mutableIntStateOf(1)
        var access by mutableStateOf<String?>("permission-epoch-1")
        val builds=java.util.concurrent.atomic.AtomicInteger()
        var published=PreparedGrid(emptyList(),-1)
        var ready=false
        val frames=mutableListOf<Pair<String?,List<String>>>()
        compose.setContent {
            val projected=backgroundLibraryProjection(
                PreparedGrid(emptyList(),-1),emptySet(),generation,
                accessGuard=access, retentionGuard=access,
                equivalentContent={old,next->old.ids==next.ids}) {
                builds.incrementAndGet()
                PreparedGrid(listOf("photo-a","photo-b"),generation)
            }
            SideEffect { published=projected.value;ready=projected.ready;frames+=access to published.ids }
            Text(published.ids.joinToString())
        }
        compose.waitUntil(5_000) {ready && published.ids.size==2}
        val warm=published
        repeat(5) { index ->
            compose.runOnIdle { generation++ }
            compose.waitUntil(5_000) { ready && builds.get()>=index+2 }
            compose.runOnIdle {assertSame(warm,published)}
        }
        assertFalse(frames.any { it.first=="permission-epoch-1" && it.second.isEmpty() })
        compose.runOnIdle {access=null}
        compose.waitForIdle()
        assertTrue(published.ids.isEmpty()) // fail closed on permission revoke
        compose.runOnIdle {access="permission-epoch-2"}
        compose.waitUntil(5_000) { ready && published.ids.size==2 }
        assertNotSame(warm,published)
    }

}
