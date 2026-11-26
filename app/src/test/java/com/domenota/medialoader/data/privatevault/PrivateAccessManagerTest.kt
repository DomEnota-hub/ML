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

    @Test fun retainedPermitRevokesFileAccessUntilPrivateReentry() = runBlocking {
        val manager = fresh()
        manager.configure(PrivateAccessMode.PIN, "123456")
        manager.setUnlockDuration(PrivateUnlockDuration.FIVE_MINUTES)
        manager.suspendAccess()
        assertFalse(manager.state.value.unlocked)
        assertTrue(runCatching { manager.requireUnlocked() }.isFailure)
        assertTrue(runCatching { manager.setUnlockDuration(PrivateUnlockDuration.SIXTY_MINUTES) }.isFailure)
        manager.preparePrivateEntry()
        assertFalse(manager.state.value.unlocked)
        assertTrue(manager.resumeRememberedAccess())
        manager.requireUnlocked()
        manager.suspendAccess()
        manager.lock()
        assertFalse(manager.resumeRememberedAccess())
    }

    @Test fun durationPersistsButAuthorizationNeverSurvivesProcessRecreation() = runBlocking {
        val manager = fresh()
        manager.configure(PrivateAccessMode.PIN, "123456")
        assertEquals(PrivateUnlockDuration.ON_EXIT, manager.unlockDuration.value)
        manager.suspendAccess()
        assertFalse(manager.resumeRememberedAccess())
        assertTrue(manager.unlockWithPin("123456"))
        manager.setUnlockDuration(PrivateUnlockDuration.UNTIL_PROCESS_END)
        manager.suspendAccess()
        val restarted = fresh()
        assertEquals(PrivateUnlockDuration.UNTIL_PROCESS_END, restarted.unlockDuration.value)
        assertFalse(restarted.state.value.unlocked)
        assertFalse(restarted.resumeRememberedAccess())
        assertTrue(manager.resumeRememberedAccess())
        manager.configure(PrivateAccessMode.PIN, "654321")
        assertEquals(PrivateUnlockDuration.UNTIL_PROCESS_END, fresh().unlockDuration.value)
    }

    @Test fun publicEntryCannotMintPermitFromUnexpectedLiveAuthorization() = runBlocking {
        val manager = fresh()
        manager.configure(PrivateAccessMode.PIN, "123456")
        manager.setUnlockDuration(PrivateUnlockDuration.SIXTY_MINUTES)
        manager.preparePrivateEntry()
        assertFalse(manager.state.value.unlocked)
        assertFalse(manager.resumeRememberedAccess())
    }

    @Test fun corruptDurationDefaultsToExitAndUnprotectedModeHasNoPermit() = runBlocking {
        context.getSharedPreferences("private_access", Context.MODE_PRIVATE).edit()
            .putString("unlock_duration", "unknown").commit()
        val manager = fresh()
        assertEquals(PrivateUnlockDuration.ON_EXIT, manager.unlockDuration.value)
        manager.configure(PrivateAccessMode.PIN, "123456")
        manager.setUnlockDuration(PrivateUnlockDuration.UNTIL_PROCESS_END)
        manager.configure(PrivateAccessMode.NONE)
        manager.suspendAccess()
        assertFalse(manager.resumeRememberedAccess())
        manager.unlockWithoutProtection()
        assertTrue(manager.state.value.unlocked)
    }

}
