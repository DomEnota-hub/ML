package com.domenota.medialoader.ui.screens

import android.app.KeyguardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.privatevault.PrivateAccessManager
import com.domenota.medialoader.data.privatevault.PrivateAccessMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateScreenUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var access: PrivateAccessManager
    private val secretName = "personal-secret-trip.jpg"

    @Before fun configureRealPin() = runBlocking {
        val context = compose.activity.applicationContext
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().clear().commit()
        access = PrivateAccessManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        access.configure(PrivateAccessMode.PIN, "123456")
        access.lock()
    }

    private fun showPrivateScreen() {
        val secret = DownloadEntity(
            id = "private-photo", providerId = "test", originalName = secretName,
            mediaType = MediaType.PHOTO, state = DownloadState.COMPLETED,
            savedUri = null, sizeBytes = 123L, createdAtEpochMillis = 1L, isPrivate = true,
        )
        compose.setContent {
            MaterialTheme {
                PrivateScreen(access, listOf(secret), onBack = {}, onOpen = {},
                    onRestore = {}, onFinishMove = {}, onDelete = {}, onRename = { _, _ -> })
            }
        }
    }

    private fun enterPin(pin: String) {
        compose.onNode(hasSetTextAction()).performTextInput(pin)
        compose.onNodeWithText("Открыть", substring = false).performClick()
    }

    private fun waitForFailedPin() {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText("Неверный PIN", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse(access.state.value.unlocked)
    }

    @Test fun secretFilenameIsHiddenUntilCorrectPinAndHiddenAgainAfterLock() {
        showPrivateScreen()
        compose.onNodeWithText("Введите PIN из 6 цифр").assertIsDisplayed()
        compose.onNodeWithText(secretName, useUnmergedTree = true).assertDoesNotExist()

        enterPin("000000")
        waitForFailedPin()
        compose.onNodeWithText(secretName, useUnmergedTree = true).assertDoesNotExist()

        enterPin("123456")
        compose.waitUntil(10_000) { access.state.value.unlocked }
        compose.onNodeWithText(secretName).assertIsDisplayed()

        compose.onNodeWithContentDescription("Заблокировать личное").performClick()
        compose.onNodeWithText("Введите PIN из 6 цифр").assertIsDisplayed()
        compose.onNodeWithText(secretName, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Настройки личного").assertDoesNotExist()
    }

    @Test fun settingsRequireAnotherSuccessfulPinBeforeModeChooserAppears() {
        showPrivateScreen()
        enterPin("123456")
        compose.waitUntil(10_000) { access.state.value.unlocked }
        compose.onNodeWithText(secretName).assertIsDisplayed()

        compose.onNodeWithContentDescription("Настройки личного").performClick()
        assertFalse(access.state.value.unlocked)
        compose.onNodeWithText("Введите PIN из 6 цифр").assertIsDisplayed()
        compose.onNodeWithText(secretName, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Выберите способ входа").assertDoesNotExist()
        compose.onNodeWithText("Без защиты", substring = false).assertDoesNotExist()

        enterPin("000000")
        waitForFailedPin()
        compose.onNodeWithText("Выберите способ входа").assertDoesNotExist()
        compose.onNodeWithText("Без защиты", substring = false).assertDoesNotExist()

        enterPin("123456")
        compose.waitUntil(10_000) { access.state.value.unlocked }
        compose.onNodeWithText("Выберите способ входа").assertIsDisplayed()
        compose.onNodeWithText("Без защиты", substring = false).assertIsDisplayed()
        compose.onNodeWithText(secretName, useUnmergedTree = true).assertDoesNotExist()
    }
    @Test fun freshlyReauthenticatedPinCanSaveSystemProtectionWithoutASecondPrompt() {
        val keyguard = compose.activity.getSystemService(KeyguardManager::class.java)
        shadowOf(keyguard).setIsDeviceSecure(true)
        showPrivateScreen()
        enterPin("123456")
        compose.waitUntil(10_000) { access.state.value.unlocked }
        compose.onNodeWithContentDescription("Настройки личного").performClick()
        enterPin("123456")
        compose.waitUntil(10_000) { access.state.value.unlocked }

        compose.onNodeWithText("Биометрия / блокировка устройства").performClick()
        compose.onNodeWithText("Сохранить", substring = false).performScrollTo().performClick()
        compose.waitUntil(10_000) { access.state.value.mode == PrivateAccessMode.BIOMETRIC }
        compose.onNodeWithText(secretName).assertIsDisplayed()
        compose.onNodeWithText("Выберите способ входа").assertDoesNotExist()
    }

}
