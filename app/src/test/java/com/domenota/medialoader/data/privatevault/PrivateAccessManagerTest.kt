package com.domenota.medialoader.data.privatevault

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PrivateAccessManagerTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun fresh(): PrivateAccessManager = PrivateAccessManager::class.java
        .getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)

    @Before fun clear() { context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().clear().commit() }

    @Test fun pinRequiresExactSixDigitsAndDoesNotPersistPlaintext() = runBlocking {
        val manager = fresh()
        assertTrue(runCatching { manager.configure(PrivateAccessMode.PIN, "123") }.isFailure)
        manager.configure(PrivateAccessMode.PIN, "123456")
        assertFalse(context.getSharedPreferences("private_access", Context.MODE_PRIVATE).all.values.contains("123456"))
        manager.lock()
        assertFalse(manager.unlockWithPin("654321"))
        assertTrue(manager.unlockWithPin("123456"))
    }

    @Test fun relaunchLocksAndCannotSwitchModeToBypassPin() = runBlocking {
        fresh().configure(PrivateAccessMode.PIN, "123456")
        val reopened = fresh()
        assertFalse(reopened.state.value.unlocked)
        assertTrue(runCatching { reopened.configure(PrivateAccessMode.NONE) }.isFailure)
        reopened.unlockWithoutProtection()
        reopened.onSystemAuthenticationSucceeded()
        assertFalse(reopened.state.value.unlocked)
        assertTrue(runCatching { reopened.requireUnlocked() }.isFailure)
    }

    @Test fun failedAttemptsSurviveRelaunchAndBlockEvenCorrectPinDuringCooldown() = runBlocking {
        val manager = fresh()
        manager.configure(PrivateAccessMode.PIN, "123456")
        manager.lock()
        repeat(5) { assertFalse(fresh().unlockWithPin("000000")) }
        assertFalse(fresh().unlockWithPin("123456"))
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit()
            .putLong("blocked_until", System.currentTimeMillis() - 1).commit()
        assertTrue(fresh().unlockWithPin("123456"))
    }

    @Test fun explicitUnprotectedChoiceStillRequiresAnUnlockOnRelaunch() = runBlocking {
        fresh().configure(PrivateAccessMode.NONE)
        val reopened = fresh()
        assertEquals(PrivateAccessMode.NONE, reopened.state.value.mode)
        assertFalse(reopened.state.value.unlocked)
        reopened.unlockWithoutProtection()
        assertTrue(reopened.state.value.unlocked)
    }

    @Test fun settingTheSamePinGeneratesANewSalt() = runBlocking {
        val manager = fresh()
        manager.configure(PrivateAccessMode.PIN, "123456")
        val prefs = context.getSharedPreferences("private_access", Context.MODE_PRIVATE)
        val salt = prefs.getString("salt", null)
        manager.configure(PrivateAccessMode.PIN, "123456")
        assertNotEquals(salt, prefs.getString("salt", null))
    }

    @Test fun unknownPersistedModeDoesNotReopenInitialSetup() {
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit().putString("mode", "corrupt").commit()
        val manager = fresh()
        assertEquals(PrivateAccessMode.PIN, manager.state.value.mode)
        assertFalse(manager.state.value.unlocked)
    }

    @Test fun pinChangeAfterUnlockReplacesOldPin() = runBlocking {
        val manager = fresh()
        manager.configure(PrivateAccessMode.PIN, "123456")
        manager.configure(PrivateAccessMode.PIN, "987654")
        manager.lock()
        assertFalse(manager.unlockWithPin("123456"))
        assertTrue(manager.unlockWithPin("987654"))
    }
}
