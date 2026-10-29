package com.domenota.medialoader.ui.components

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.domenota.medialoader.data.privatevault.PrivateAccessManager
import com.domenota.medialoader.data.privatevault.PrivateAccessMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [PrivateAccessGateTest.PlatformPrompt::class])
@LooperMode(LooperMode.Mode.PAUSED)
class PrivateAccessGateTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var manager: PrivateAccessManager
    private val owner = object : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }

    @Before fun setup() = runBlocking {
        val context = compose.activity.applicationContext
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().clear().commit()
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        manager = PrivateAccessManager::class.java.getDeclaredConstructor(Context::class.java)
            .apply { isAccessible = true }.newInstance(context)
        manager.configure(PrivateAccessMode.BIOMETRIC)
        manager.lock()
        PlatformPrompt.requests = 0
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
    }

    private fun show(autoAuthenticate: Boolean = true) {
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                MaterialTheme { PrivateAccessGate(manager, autoAuthenticate = autoAuthenticate) }
            }
        }
    }
    private fun resume() = compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
    private fun stop() = compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }

    @Test fun entryRequestsRealPlatformPromptOnlyWhenResumedAndCancellationDoesNotLoop() {
        show()
        compose.waitForIdle()
        assertEquals(0, PlatformPrompt.requests)
        resume()
        compose.waitForIdle()
        assertEquals(1, PlatformPrompt.requests)
        compose.runOnUiThread {
            PlatformPrompt.callback.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_CANCELED, "Отмена")
        }
        compose.onNodeWithText("Повторить вход").assertIsDisplayed()
        stop(); resume()
        compose.waitForIdle()
        assertEquals(1, PlatformPrompt.requests)
        assertFalse(manager.state.value.unlocked)
        compose.onNodeWithText("Повторить вход").performClick()
        assertEquals(2, PlatformPrompt.requests)
    }

    @Test fun credentialActivityStopResumeDoesNotRequestAgainAndCancelKeepsLocked() {
        show(); resume()
        compose.waitForIdle()
        assertEquals(1, PlatformPrompt.requests)
        compose.runOnUiThread {
            PlatformPrompt.callback.onAuthenticationError(BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS, "No biometrics")
        }
        compose.waitForIdle()
        val launched = shadowOf(compose.activity).nextStartedActivityForResult
        assertNotNull("Real Android credential activity must launch", launched)
        assertEquals("android.app.action.CONFIRM_DEVICE_CREDENTIAL", launched.intent.action)
        stop(); resume()
        compose.waitForIdle()
        assertEquals(1, PlatformPrompt.requests)
        compose.runOnUiThread {
            shadowOf(compose.activity).receiveResult(launched.intent, Activity.RESULT_CANCELED, Intent())
        }
        compose.onNodeWithText("Повторить вход").assertIsDisplayed()
        assertFalse(manager.state.value.unlocked)
        assertEquals(1, PlatformPrompt.requests)
    }

    @Test fun manualLockWaitsForAnExplicitRequest() {
        show(autoAuthenticate = false)
        resume()
        compose.waitForIdle()
        assertEquals(0, PlatformPrompt.requests)
        stop(); resume()
        compose.waitForIdle()
        assertEquals(0, PlatformPrompt.requests)
        compose.onNodeWithText("Подтвердить вход").performClick()
        assertEquals(1, PlatformPrompt.requests)
        assertFalse(manager.state.value.unlocked)
    }

    @Implements(BiometricPrompt::class)
    class PlatformPrompt {
        @Implementation fun authenticate(signal: CancellationSignal, executor: Executor, result: BiometricPrompt.AuthenticationCallback) {
            requests++
            callback = result
        }
        companion object {
            var requests = 0
            lateinit var callback: BiometricPrompt.AuthenticationCallback
        }
    }
}
