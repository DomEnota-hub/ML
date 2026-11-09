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

    @Test fun `known balances persist per key and never expose request URLs`() {
        val prefs = context.getSharedPreferences("gapi-test", 0)
        val ui = context.getSharedPreferences("ui-test", 0)
        val second = builtin.copy(id = "builtin-two", token = "second-secret")
        fun reopen() = GapiKeyStore(prefs, ui, listOf(builtin, second), { now })
        val store = reopen()
        assertEquals(listOf(112, 200), store.snapshot().map { it.remaining })
        store.recordResponse("/v2/vk?url=private-url", builtin.token, 200)
        store.recordResponse("/v2/instagram/media?url=private-url", second.token, 404)
        assertEquals(listOf(111, 199), reopen().snapshot().map { it.remaining })
        assertFalse(prefs.all.toString().contains("private-url"))
        store.recordResponse("/v2/vk?url=private-url", builtin.token, 429)
        store.recordResponse("/v2/vk?url=private-url", builtin.token, 502)
        store.recordResponse("/v2/vk?url=private-url", builtin.token, null)
        assertEquals(111, reopen().snapshot().first().remaining)
        store.move(second.id, -1)
        assertEquals(199, reopen().snapshot().first().remaining)
        store.markExhausted(second.id)
        assertEquals(0, reopen().snapshot().first().remaining)
        now = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        assertEquals(listOf(200, 200), reopen().snapshot().map { it.remaining })
    }

    @Test fun `builtins follow developer mode independently of extended logging`() {
        val store = store()
        assertTrue(store.add("Мой ключ", "custom-secret"))
        val ui = context.getSharedPreferences("ui-test", 0)
        assertEquals(listOf("Встроенный", "Мой ключ"), store.eligible().map { it.name })
        ui.edit().putBoolean("developer_visible", true).commit()
        assertTrue(store.builtinAllowed())
        assertEquals(2, store.eligible().size)
        ui.edit().putBoolean("extended_app_log", true).commit()
        ui.edit().putBoolean("extended_app_log", false).commit()
        assertTrue(store.builtinAllowed())
        assertEquals(2, store.eligible().size)
        ui.edit().putBoolean("developer_visible", false).commit()
        assertTrue(store.builtinAllowed())
        assertEquals(2, store.eligible().size)
    }

    @Test fun `exhaustion persists and expires at the next UTC month`() {
        val store = store()
        store.add("Мой ключ", "custom-secret")
        val id = store.eligible().single { !it.builtIn }.id
        store.markExhausted(id)
        assertTrue(store().eligible().none { !it.builtIn })
        now = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        assertEquals(id, store().eligible().single { !it.builtIn }.id)
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
        val key = store.eligible().single { !it.builtIn }
        store.pause(key.id, now + 10_000)
        assertTrue(store().eligible().none { !it.builtIn })
        assertEquals(now + 10_000, store.nextAvailableAtMillis())
        now += 10_001
        assertEquals(key.id, store().eligible().single { !it.builtIn }.id)
        store.markInvalid(key.id)
        assertTrue(store().eligible().none { !it.builtIn })
        assertFalse(key.toString().contains("custom-secret"))
    }

    @Test fun `reset status clears all persisted blocks without changing priority or enabled state`() {
        val store = store()
        store.add("Ключ", "custom-secret")
        val key = store.eligible().single { !it.builtIn }
        store.markInvalid(key.id)
        store.markExhausted(key.id)
        store.pause(key.id, now + 60_000)
        assertTrue(store.resetStatus(key.id))
        val restored = store().snapshot().single { it.id == key.id }
        assertEquals(key.id, restored.id)
        assertNull(restored.exhaustedMonth)
        assertFalse(restored.invalid)
        assertEquals(0L, restored.pauseUntilMillis)
        assertFalse(store.resetStatus("missing"))
    }

    @Test fun `balance exhaustion selects next built-in key`() {
        val prefs = context.getSharedPreferences("gapi-test", 0)
        val ui = context.getSharedPreferences("ui-test", 0)
        val second = builtin.copy(id = "builtin-two", token = "second-test-token")
        fun keys() = GapiKeyStore(prefs, ui, listOf(builtin, second), { now })
        assertEquals(listOf(112, 200), keys().snapshot().map { it.remaining })
        repeat(112) {
            keys().recordResponse("/v2/vk?url=redacted", builtin.token, 200)
        }
        assertEquals(listOf(0, 200), keys().snapshot().map { it.remaining })
        assertEquals(listOf("builtin-two"), keys().eligible().map { it.id })
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(),
            keys().nextAvailableAtMillis())
        now = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        assertEquals(listOf(200, 200), keys().snapshot().map { it.remaining })
        assertEquals(listOf("builtin-one", "builtin-two"), keys().eligible().map { it.id })
    }

    @Test fun `legacy November first key balance migrates once and preserves prior spending`() {
        val prefs = context.getSharedPreferences("gapi-test", 0)
        val ui = context.getSharedPreferences("ui-test", 0)
        val second = builtin.copy(id = "builtin-two", token = "second-secret")
        fun reopen() = GapiKeyStore(prefs, ui, listOf(builtin, second), { now })
        // Old QA began at 156 and has already spent 56 credits on this device.
        prefs.edit().putString("usage:builtin-one",
            """{"month":"2026-11","remaining":100,"last":"previous-request"}""").commit()
        assertEquals(listOf(56, 200), reopen().snapshot().map { it.remaining })
        assertEquals(56, reopen().snapshot().first().remaining)
        val migrated = org.json.JSONObject(prefs.getString("usage:builtin-one", "{}")!!)
        assertEquals(112, migrated.getInt("seedBaseline"))
        assertEquals(56, migrated.getInt("remaining"))
        assertEquals("previous-request", migrated.getString("last"))
        reopen().recordResponse("/v2/vk?url=private-url", builtin.token, 200)
        assertEquals(55, reopen().snapshot().first().remaining)
        reopen().move(second.id, -1)
        assertEquals(listOf(200, 55), reopen().snapshot().map { it.remaining })
        assertEquals("builtin-two", reopen().eligible().first().id)
        now = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli()
        assertEquals(listOf(200, 200), reopen().snapshot().map { it.remaining })
    }

    @Test fun `new November usage keeps 112 seed without a second migration`() {
        val prefs = context.getSharedPreferences("gapi-test", 0)
        val ui = context.getSharedPreferences("ui-test", 0)
        fun reopen() = GapiKeyStore(prefs, ui, listOf(builtin), { now })
        assertEquals(112, reopen().snapshot().single().remaining)
        reopen().recordResponse("/v2/instagram/media?url=private-url", builtin.token, 200)
        assertEquals(111, reopen().snapshot().single().remaining)
        assertEquals(111, reopen().snapshot().single().remaining)
        reopen().recordResponse("/v2/instagram/media?url=private-url", builtin.token, 200)
        assertEquals(110, reopen().snapshot().single().remaining)
        val saved = org.json.JSONObject(prefs.getString("usage:builtin-one", "{}")!!)
        assertEquals(112, saved.getInt("seedBaseline"))
        assertEquals(110, saved.getInt("remaining"))
        assertFalse(saved.toString().contains("private-url"))
    }

    @Test fun `legacy exhausted balance stays zero after correction`() {
        val prefs = context.getSharedPreferences("gapi-test", 0)
        val ui = context.getSharedPreferences("ui-test", 0)
        prefs.edit().putString("usage:builtin-one",
            """{"month":"2026-11","remaining":0}""").commit()
        val reopened = GapiKeyStore(prefs, ui, listOf(builtin), { now })
        assertEquals(0, reopened.snapshot().single().remaining)
        assertTrue(reopened.eligible().isEmpty())
        assertEquals(Instant.parse("2026-12-01T00:00:00Z").toEpochMilli(),
            reopened.nextAvailableAtMillis())
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
