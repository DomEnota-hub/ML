package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaSwipePagesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun opensSelectedFileAndSwipesWithoutResettingToInitialPage() {
        val rows = (1..3).map { DownloadEntity("photo-$it", "device", "$it.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, "file:///missing-$it.jpg", 123456, 0) }
        var current by mutableStateOf("photo-2")
        compose.setContent { MaterialTheme {
            Box(Modifier.fillMaxSize()) {
                MediaSwipePages(rows[1], rows, { current = it.id }, Modifier.fillMaxSize())
            }
        } }
        compose.waitForIdle()
        assertEquals("photo-2", current)
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals("photo-3", current)
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals("photo-2", current)
    }
}
