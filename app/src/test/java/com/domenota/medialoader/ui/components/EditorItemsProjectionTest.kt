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
}
