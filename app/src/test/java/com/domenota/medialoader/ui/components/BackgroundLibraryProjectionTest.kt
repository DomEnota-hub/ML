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

}
