package com.domenota.medialoader.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
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
class EditorItemsProjectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun lockingNeverPublishesPreviousPrivateProjectionEvenDuringBackgroundRebuild() {
        val secret = DownloadEntity("secret", "device", "private.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, "content://private/42", 1024L, 0L, isPrivate = true)
        var rows by mutableStateOf(listOf(secret))
        var shown by mutableStateOf(emptyList<String>())
        val leaks = mutableListOf<Boolean>()
        compose.setContent {
            val projected = rememberEditorItems(rows)
            SideEffect {
                shown = projected.map { it.id }
                if (rows.isEmpty()) leaks.add(projected.isNotEmpty())
            }
            Text(projected.size.toString())
        }
        compose.waitUntil(5000) { shown == listOf("secret") }
        compose.runOnIdle { rows = emptyList() }
        compose.waitForIdle()
        assertTrue(leaks.isNotEmpty())
        assertFalse("Private rows cannot survive a locked input", leaks.any { it })
        assertTrue(shown.isEmpty())
    }
    @Test fun refreshedVideoMetadataDoesNotBlankAnAlreadyVisiblePicker() {
        val video = DownloadEntity("vid-1", "device", "movie.mp4", MediaType.VIDEO,
            DownloadState.COMPLETED, "content://media/external/video/media/42", 1024L, 0L)
        var rows by mutableStateOf(listOf(video))
        var shown by mutableStateOf(emptyList<String>())
        var monitoring = false
        val emptyFrames = mutableListOf<Boolean>()
        compose.setContent {
            val projected = rememberEditorItems(rows)
            SideEffect {
                shown = projected.map { it.id }
                if (monitoring) emptyFrames.add(projected.isEmpty())
            }
            Text(projected.size.toString())
        }
        compose.waitUntil(5000) { shown == listOf(video.id) }
        compose.runOnIdle {
            monitoring = true
            rows = listOf(video.copy(sizeBytes = 2048L))
        }
        compose.waitForIdle()
        assertTrue("Should observe at least one refresh frame", emptyFrames.isNotEmpty())
        assertFalse("Unchanged video must not blink to zero while mapping runs", emptyFrames.any { it })
        assertEquals(listOf(video.id), shown)
    }

    @Test fun removedOrRevokedVideoIsHiddenImmediately() {
        val video = DownloadEntity("vid-2", "device", "movie.mp4", MediaType.VIDEO,
            DownloadState.COMPLETED, "content://media/external/video/media/43", 1024L, 0L)
        var rows by mutableStateOf(listOf(video))
        var shown by mutableStateOf(emptyList<String>())
        val revokedFrames = mutableListOf<Boolean>()
        var revoked = false
        compose.setContent {
            val projected = rememberEditorItems(rows)
            SideEffect {
                shown = projected.map { it.id }
                if (revoked) revokedFrames.add(projected.isNotEmpty())
            }
            Text(projected.size.toString())
        }
        compose.waitUntil(5000) { shown == listOf(video.id) }
        compose.runOnIdle {
            revoked = true
            rows = listOf(video.copy(hidden = true))
        }
        compose.waitForIdle()
        assertFalse("Hidden video must disappear without a stale frame", revokedFrames.any { it })
        assertTrue(shown.isEmpty())
    }

}
