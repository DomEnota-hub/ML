package com.domenota.medialoader.data.gapi

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.domenota.medialoader.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.UUID

/** Tokens are credentials. Never include them in logs, diagnostics or exception messages. */
data class GapiKey(
    val id: String,
    val name: String,
    val token: String,
    val enabled: Boolean,
    val builtIn: Boolean,
    val exhaustedMonth: String? = null,
    val invalid: Boolean = false,
    val pauseUntilMillis: Long = 0,
) {
    override fun toString(): String = "GapiKey(id=$id, name=$name, token=••••, enabled=$enabled, " +
        "builtIn=$builtIn, exhaustedMonth=$exhaustedMonth, invalid=$invalid, pauseUntilMillis=$pauseUntilMillis)"
}

/** Dedicated Keystore encrypted preferences; a Keystore failure never falls back to plaintext. */
class GapiKeyStore internal constructor(
    private val preferences: SharedPreferences?,
    private val uiPreferences: SharedPreferences,
    private val builtins: List<GapiKey>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        encryptedPreferences(context.applicationContext),
        context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE),
        parseBuiltins(BuildConfig.GAPI_BUILTIN_KEYS_JSON),
    )

    val storageAvailable: Boolean get() = preferences != null

    fun currentMonth(): String = YearMonth.from(Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC)).toString()

    // Both switches are deliberately read each time: this store can outlive the Settings screen.
    fun builtinAllowed(): Boolean = uiPreferences.getBoolean(
        "developer_visible", uiPreferences.getBoolean("logs_unlocked", false),
    ) && uiPreferences.getBoolean("extended_app_log", false)

    var fallbackEnabled: Boolean
        get() = preferences?.getBoolean("account_fallback", true) ?: true
        set(value) {
            synchronized(lock) { preferences?.edit()?.putBoolean("account_fallback", value)?.commit() }
        }

    fun snapshot(): List<GapiKey> = synchronized(lock) {
        val removed = preferences?.getStringSet("removed_builtins", emptySet()).orEmpty()
        val availableBuiltins = builtins.filterNot { it.id in removed }.associateBy { it.id }
        val saved = runCatching { JSONArray(preferences?.getString("keys", "[]") ?: "[]") }
            .getOrElse { JSONArray() }
        val keys = mutableListOf<GapiKey>()
        for (index in 0 until saved.length()) {
            val item = saved.optJSONObject(index) ?: continue
            val id = item.optString("id")
            val builtin = item.optBoolean("builtIn")
            val definition = if (builtin) availableBuiltins[id] ?: continue else null
            val token = definition?.token ?: item.optString("key")
            if (id.isBlank() || token.isBlank()) continue
            keys += GapiKey(
                id, item.optString("name").ifBlank { definition?.name ?: "Ключ GAPI" }, token,
                item.optBoolean("enabled", true), builtin,
                item.optString("exhaustedMonth").takeIf { it.isNotBlank() },
                item.optBoolean("invalid"), item.optLong("pauseUntilMillis"),
            )
        }
        availableBuiltins.values.filterNot { builtin -> keys.any { it.id == builtin.id } }
            .forEach(keys::add)
        keys.distinctBy { it.id }.distinctBy { it.token }
    }

    fun eligible(): List<GapiKey> {
        if (!storageAvailable) return emptyList()
        val allowBuiltin = builtinAllowed()
        val month = currentMonth()
        val time = now()
        return snapshot().filter {
            it.enabled && !it.invalid && it.exhaustedMonth != month &&
                it.pauseUntilMillis <= time && (!it.builtIn || allowBuiltin)
        }
    }

    /** Earliest scheduled recovery for a currently enabled key, including monthly quota reset. */
    fun nextAvailableAtMillis(): Long? {
        if (!storageAvailable) return null
        val allowBuiltin = builtinAllowed()
        val time = now()
        val month = currentMonth()
        val nextMonth = YearMonth.parse(month).plusMonths(1).atDay(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return snapshot().asSequence().filter {
            it.enabled && !it.invalid && (!it.builtIn || allowBuiltin)
        }.map {
            maxOf(it.pauseUntilMillis, if (it.exhaustedMonth == month) nextMonth else 0L)
        }.filter { it > time }.minOrNull()
    }

    fun add(name: String, token: String): Boolean = synchronized(lock) {
        val cleanToken = token.trim()
        val cleanName = name.trim()
        if (cleanName.isBlank() || cleanToken.isBlank() || cleanToken.any(Char::isWhitespace)) return false
        val keys = snapshot()
        // Removed builtin credentials must not bypass their visibility gate when pasted as custom keys.
        if (keys.any { it.token == cleanToken } || builtins.any { it.token == cleanToken }) return false
        persist(keys + GapiKey("custom-${UUID.randomUUID()}", cleanName.take(80), cleanToken, true, false))
    }

    fun rename(id: String, name: String): Boolean {
        val clean = name.trim().take(80)
        if (clean.isBlank()) return false
        return update(id) { it.copy(name = clean) }
    }

    fun setEnabled(id: String, enabled: Boolean): Boolean = update(id) { it.copy(enabled = enabled) }

    fun remove(id: String): Boolean = synchronized(lock) {
        val keys = snapshot()
        val key = keys.firstOrNull { it.id == id } ?: return false
        val removed = preferences?.getStringSet("removed_builtins", emptySet()).orEmpty().toMutableSet()
        if (key.builtIn) removed += id
        persist(keys.filterNot { it.id == id }, removed)
    }

    /** Negative offsets raise priority, positive offsets lower it. */
    fun move(id: String, offset: Int): Boolean = synchronized(lock) {
        val keys = snapshot().toMutableList()
        val from = keys.indexOfFirst { it.id == id }
        if (from < 0) return false
        val to = (from + offset).coerceIn(0, keys.lastIndex)
        if (from == to) return true
        keys.add(to, keys.removeAt(from))
        persist(keys)
    }

    fun resetStatus(id: String): Boolean = update(id) {
        it.copy(invalid = false, exhaustedMonth = null, pauseUntilMillis = 0L)
    }

    fun markExhausted(id: String) { update(id) { it.copy(exhaustedMonth = currentMonth()) } }
    fun markInvalid(id: String) { update(id) { it.copy(invalid = true) } }
    fun pause(id: String, untilMillis: Long) {
        update(id) { it.copy(pauseUntilMillis = maxOf(it.pauseUntilMillis, untilMillis)) }
    }
    fun markSuccess(id: String) { update(id) { it.copy(pauseUntilMillis = 0L) } }

    private fun update(id: String, transform: (GapiKey) -> GapiKey): Boolean = synchronized(lock) {
        val keys = snapshot()
        if (keys.none { it.id == id }) return false
        persist(keys.map { if (it.id == id) transform(it) else it })
    }

    private fun persist(keys: List<GapiKey>, removed: Set<String>? = null): Boolean {
        val prefs = preferences ?: return false
        val array = JSONArray()
        keys.forEach { key ->
            array.put(JSONObject().apply {
                put("id", key.id); put("name", key.name); put("enabled", key.enabled)
                put("builtIn", key.builtIn)
                // BuildConfig already supplies builtin secrets; save only their settings and status.
                if (!key.builtIn) put("key", key.token)
                put("exhaustedMonth", key.exhaustedMonth ?: "")
                put("invalid", key.invalid); put("pauseUntilMillis", key.pauseUntilMillis)
            })
        }
        return runCatching {
            prefs.edit().putString("keys", array.toString()).apply {
                if (removed != null) putStringSet("removed_builtins", removed)
            }.commit()
        }.getOrDefault(false)
    }

    private companion object {
        val lock = Any()
        fun encryptedPreferences(context: Context): SharedPreferences? = runCatching {
            EncryptedSharedPreferences.create(
                context, "gapi_credentials",
                MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()

        fun parseBuiltins(raw: String): List<GapiKey> = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val id = item.optString("id").trim()
                val token = item.optString("key").trim()
                if (id.isBlank() || token.isBlank()) null else GapiKey(
                    id, item.optString("name").ifBlank { "Встроенный ключ ${index + 1}" }, token, true, true,
                )
            }.distinctBy { it.id }.distinctBy { it.token }
        }.getOrDefault(emptyList())
    }
}
