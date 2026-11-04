package com.domenota.medialoader.data.gapi

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GapiKeyStoreTest {
    private lateinit var context: Context
    private var now = Instant.parse("2026-11-30T23:59:59Z").toEpochMilli()
    private val builtin = GapiKey("builtin-one", "Встроенный", "built-in-secret", true, true)

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("gapi-test", 0).edit().clear().commit()
        context.getSharedPreferences("ui-test", 0).edit().clear().commit()
    }

    private fun store() = GapiKeyStore(
        context.getSharedPreferences("gapi-test", 0),
        context.getSharedPreferences("ui-test", 0), listOf(builtin), { now },
    )

    @Test fun `builtins require both current developer and extended log preferences`() {
        val store = store()
        assertTrue(store.add("Мой ключ", "custom-secret"))
        val ui = context.getSharedPreferences("ui-test", 0)
        assertEquals(listOf("Мой ключ"), store.eligible().map { it.name })
        ui.edit().putBoolean("logs_unlocked", true).commit()
        assertFalse(store.builtinAllowed())
        ui.edit().putBoolean("extended_app_log", true).commit()
        assertEquals(2, store.eligible().size)
        ui.edit().putBoolean("developer_visible", false).commit()
        assertFalse(store.builtinAllowed())
        assertEquals(1, store.eligible().size)
    }

    @Test fun `exhaustion persists and expires at the next UTC month`() {
        val store = store()
        store.add("Мой ключ", "custom-secret")
        val id = store.eligible().single().id
        store.markExhausted(id)
        assertTrue(store().eligible().isEmpty())
        now = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        assertEquals(id, store().eligible().single().id)
    }

    @Test fun `priority and builtin removal persist while duplicate tokens are refused`() {
        val store = store()
        assertTrue(store.add("Первый", "first-secret"))
        assertFalse(store.add("Дубликат", "first-secret"))
        assertFalse(store.add("Встроенный дубликат", "built-in-secret"))
        assertTrue(store.add("Второй", "second-secret"))
        val second = store.snapshot().last()
        assertTrue(store.move(second.id, -1))
        assertTrue(store.remove(builtin.id))
        assertEquals(listOf("Второй", "Первый"), store().snapshot().map { it.name })
        store.setEnabled(second.id, false)
        assertEquals(listOf("Первый"), store().eligible().map { it.name })
    }

    @Test fun `pause and invalid status persist without exposing tokens`() {
        val store = store()
        store.add("Ключ", "custom-secret")
        val key = store.eligible().single()
        store.pause(key.id, now + 10_000)
        assertTrue(store().eligible().isEmpty())
        assertEquals(now + 10_000, store.nextAvailableAtMillis())
        now += 10_001
        assertEquals(key.id, store().eligible().single().id)
        store.markInvalid(key.id)
        assertTrue(store().eligible().isEmpty())
        assertFalse(key.toString().contains("custom-secret"))
    }

    @Test fun `reset status clears all persisted blocks without changing priority or enabled state`() {
        val store = store()
        store.add("Ключ", "custom-secret")
        val key = store.eligible().single()
        store.markInvalid(key.id)
        store.markExhausted(key.id)
        store.pause(key.id, now + 60_000)
        assertTrue(store.resetStatus(key.id))
        val restored = store().eligible().single()
        assertEquals(key.id, restored.id)
        assertNull(restored.exhaustedMonth)
        assertFalse(restored.invalid)
        assertEquals(0L, restored.pauseUntilMillis)
        assertFalse(store.resetStatus("missing"))
    }

    @Test fun `unavailable encrypted storage never makes builtin credentials eligible`() {
        val ui = context.getSharedPreferences("ui-test", 0)
        ui.edit().putBoolean("developer_visible", true).putBoolean("extended_app_log", true).commit()
        val store = GapiKeyStore(null, ui, listOf(builtin), { now })
        assertFalse(store.storageAvailable)
        assertTrue(store.builtinAllowed())
        assertTrue(store.eligible().isEmpty())
        assertNull(store.nextAvailableAtMillis())
        assertFalse(store.resetStatus(builtin.id))
    }

}
