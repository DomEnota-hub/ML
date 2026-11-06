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
    val remaining: Int? = null,
    val monthlyLimit: Int? = null,
    val lastUsage: String? = null,
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
        parseBuiltins(readBundledKeys(context.applicationContext)),
    )

    val storageAvailable: Boolean get() = preferences != null

    fun currentMonth(): String = YearMonth.from(Instant.ofEpochMilli(now()).atZone(ZoneOffset.UTC)).toString()

    // Public setting and private credentials are unrelated: developer mode must
    // never disable bundled keys or hide the user's selected download method.
    // Kept for callers migrated from the old developer-only settings UI.
    fun builtinAllowed(): Boolean = true

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
        keys.distinctBy { it.id }.distinctBy { it.token }.map { key ->
            val usage = runCatching { JSONObject(preferences?.getString("usage:${key.id}", "{}") ?: "{}") }.getOrDefault(JSONObject())
            val builtinIndex = builtins.indexOfFirst { it.id == key.id }
            val limit = if (builtinIndex in 0..1) 200 else null
            val seed = if (currentMonth() == "2026-11") {
                when (builtinIndex) { 0 -> 156; 1 -> 200; else -> null }
            } else limit
            val remaining = if (usage.optString("month") == currentMonth() && usage.has("remaining"))
                usage.optInt("remaining").takeIf { it >= 0 } else seed
            key.copy(remaining = if (key.exhaustedMonth == currentMonth()) 0 else remaining,
                monthlyLimit = limit, lastUsage = usage.optString("last").takeIf { it.isNotBlank() })
        }
    }

    fun eligible(): List<GapiKey> {
        if (!storageAvailable) return emptyList()
        val month = currentMonth()
        val time = now()
        // A locally exhausted balance is a definitive no-go for this month.
        // The list preserves the user's priority, so key #1 precedes key #2.
        return snapshot().filter {
            it.enabled && !it.invalid && it.exhaustedMonth != month &&
                it.pauseUntilMillis <= time && (it.remaining == null || it.remaining > 0)
        }
    }

    /** Earliest scheduled recovery for a currently enabled key, including monthly quota reset. */
    fun nextAvailableAtMillis(): Long? {
        if (!storageAvailable) return null
        val time = now()
        val month = currentMonth()
        val nextMonth = YearMonth.parse(month).plusMonths(1).atDay(1)
            .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return snapshot().asSequence().filter {
            it.enabled && !it.invalid
        }.map {
            maxOf(it.pauseUntilMillis,
                if (it.exhaustedMonth == month || it.remaining == 0) nextMonth else 0L)
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

    fun markExhausted(id: String) = synchronized(lock) {
        update(id) { it.copy(exhaustedMonth = currentMonth()) }
        val usage = runCatching { JSONObject(preferences?.getString("usage:$id", "{}") ?: "{}") }.getOrDefault(JSONObject())
        usage.put("month", currentMonth()).put("remaining", 0)
        preferences?.edit()?.putString("usage:$id", usage.toString())?.commit()
        Unit
    }
    fun markInvalid(id: String) { update(id) { it.copy(invalid = true) } }
    fun pause(id: String, untilMillis: Long) {
        update(id) { it.copy(pauseUntilMillis = maxOf(it.pauseUntilMillis, untilMillis)) }
    }
    fun markSuccess(id: String) { update(id) { it.copy(pauseUntilMillis = 0L) } }

    /** All actual HTTP calls pass here, including retries and successful-but-unparseable replies. */
    internal suspend fun request(path: String, token: String): com.domenota.medialoader.core.provider.GapiResponse {
        val response = try {
            com.domenota.medialoader.core.provider.fetchGapi(path, token)
        } catch (e: kotlinx.coroutines.CancellationException) {
            recordResponse(path, token, null)
            throw e
        } catch (e: Exception) {
            recordResponse(path, token, null)
            throw e
        }
        recordResponse(path, token, response.status)
        return response
    }

    internal fun recordResponse(path: String, token: String, status: Int?) {
        synchronized(lock) {
            val key = snapshot().firstOrNull { it.token == token }
            if (key != null) {
                val endpoint = path.substringBefore('?')
                val cost = GapiUsagePolicy.cost(path, status ?: 0)
                val remaining = key.remaining?.let { (it - cost).coerceAtLeast(0) }
                val source = when {
                    endpoint.contains("instagram") -> "Instagram"
                    endpoint.contains("youtube") -> "YouTube"
                    endpoint.endsWith("/vk") -> "VK"
                    else -> "GAPI"
                }
                val last = "${java.time.Instant.ofEpochMilli(now())} · $source · ${status?.let { "HTTP $it · расход $cost" } ?: "Ответ не получен · расход неизвестен"}"
                val usage = JSONObject().put("month", currentMonth()).put("last", last)
                if (remaining != null) usage.put("remaining", remaining)
                val saved = preferences?.edit()?.putString("usage:${key.id}", usage.toString())?.commit() == true
                com.domenota.medialoader.core.logging.AppLog.i("GapiUsage",
                    "method=gapi · keyId=${key.id} · endpoint=$endpoint · status=${status ?: "unconfirmed"} · cost=$cost · remaining=${remaining ?: "unknown"} · saved=$saved")
            }
        }
    }

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

        /**
         * The final QA APK receives a private asset immediately before local signing.
         * Source control and public CI artifacts contain no API credentials.
         * A locally configured developer build may still use BuildConfig as before.
         */
        private fun readBundledKeys(context: Context): String {
            val assetJson = runCatching {
                context.assets.open("gapi-builtin-keys.json").bufferedReader(Charsets.UTF_8).use {
                    it.readText().takeIf { json -> json.length in 2..16_384 }
                }
            }.getOrNull()
            return assetJson ?: BuildConfig.GAPI_BUILTIN_KEYS_JSON
        }

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
