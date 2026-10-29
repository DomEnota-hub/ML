package com.domenota.medialoader.ui.screens

import android.content.Context
import android.app.KeyguardManager
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.domenota.medialoader.ui.components.PrivateAccessGateTest
import org.robolectric.Shadows.shadowOf
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.privatevault.PrivateAccessManager
import com.domenota.medialoader.data.privatevault.PrivateAccessMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi",
    shadows = [PrivateAccessGateTest.PlatformPrompt::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateGroupsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val restored = mutableListOf<String>()
    private fun show(count: Int = 30, locked: Boolean = false, externalBusy: Boolean = false) {
        val context = compose.activity.applicationContext
        context.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().clear().commit()
        val access = PrivateAccessManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        runBlocking { access.configure(PrivateAccessMode.PIN, "123456"); if (locked) access.lock() }
        val rows = (1..count).map { DownloadEntity(id = "p$it", providerId = "test",
            originalName = "secret-$it.jpg", mediaType = MediaType.PHOTO,
            state = DownloadState.COMPLETED, savedUri = null, sizeBytes = 1L,
            createdAtEpochMillis = 1L, isPrivate = true, groupId = "post") }
        compose.setContent { MaterialTheme { PrivateScreen(access, rows, {}, {},
            { restored += it }, {}, {}, { _, _ -> }, externalBusy = externalBusy) } }
    }
    @Test fun collapsedGroupRestoreIncludesMembersBeyondVisibleLimit() {
        show()
        compose.onNodeWithText("secret-1.jpg").assertDoesNotExist()
        compose.onNodeWithContentDescription("Действия с группой").performClick()
        compose.onNodeWithText("Вернуть из личного").performClick()
        compose.waitUntil(10_000) { restored.size == 30 }
        assertEquals((1..30).map { "p$it" }, restored)
    }
    @Test fun privateGridShowsIndividualItemsAndPersistsPreference() {
        show(4)
        compose.onNodeWithContentDescription("Показать сеткой").performClick()
        compose.onAllNodesWithContentDescription("Действия с группой").assertCountEquals(0)
        compose.onNodeWithContentDescription("Показать списком").assertIsDisplayed()
        val cells = (1..4).map { compose.onNodeWithContentDescription("secret-$it.jpg").fetchSemanticsNode().boundsInRoot }
        assertTrue(cells.zipWithNext().all { (left, right) -> left.right <= right.left && left.top == right.top })
        assertTrue(cells.all { kotlin.math.abs(it.width - cells.first().width) < 1f })
        assertEquals(true, compose.activity.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("private_grid", false))
        compose.onNodeWithContentDescription("Заблокировать личное").performClick()
        compose.onNodeWithContentDescription("Показать списком").assertDoesNotExist()
        compose.onNodeWithText("secret-1.jpg", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun lockedPrivateGroupsHideGroupAndFileActions() {
        show(4, locked = true)
        compose.onNodeWithContentDescription("Действия с группой").assertDoesNotExist()
        compose.onNodeWithContentDescription("Показать сеткой").assertDoesNotExist()
        compose.onNodeWithText("Публикация · 4 файлов").assertDoesNotExist()
        compose.onNodeWithText("secret-1.jpg", useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun collapsedPrivateGroupDeleteWarnsWithWholeGroupCount() {
        show()
        compose.onNodeWithContentDescription("Действия с группой").performClick()
        compose.onNodeWithText("Удалить навсегда").performClick()
        compose.onNodeWithText("Удалить личные файлы (30)?").assertIsDisplayed()
        compose.onNodeWithText("Файлы (30) будут удалены навсегда. Вернуть их из корзины не получится.").assertIsDisplayed()
    }

    @Test fun manualBiometricLockRemainsSuppressedAfterStateRestoration() {
        val context = compose.activity.applicationContext
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        val access = PrivateAccessManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        runBlocking { access.configure(PrivateAccessMode.BIOMETRIC) }
        PrivateAccessGateTest.PlatformPrompt.requests = 0
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme { PrivateScreen(access, emptyList(), {}, {}, {}, {}, {}, { _, _ -> }) }
        }
        compose.onNodeWithContentDescription("Заблокировать личное").performClick()
        compose.waitForIdle()
        assertEquals(0, PrivateAccessGateTest.PlatformPrompt.requests)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertEquals("Manual lock must survive restored composition", 0, PrivateAccessGateTest.PlatformPrompt.requests)
        compose.onNodeWithText("Подтвердить вход").performClick()
        assertEquals(1, PrivateAccessGateTest.PlatformPrompt.requests)
    }

    @Test fun transferAfterFirstSetupDoesNotAskForReadAccessWhenLocked() {
        show(4, locked = true, externalBusy = true)
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText("Перемещаем файлы…").assertIsDisplayed()
    }

}
