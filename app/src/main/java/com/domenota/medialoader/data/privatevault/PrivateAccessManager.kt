package com.domenota.medialoader.data.privatevault

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec


enum class PrivateAccessMode { UNCONFIGURED, NONE, PIN, BIOMETRIC }
data class PrivateAccessState(val mode: PrivateAccessMode, val unlocked: Boolean)

/** Process-local authorization; only salted PIN hashes and throttling state are persisted. */
class PrivateAccessManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("private_access", Context.MODE_PRIVATE)
    private val monitor = Any()
    private var revision = 0L
    private val mutableState = MutableStateFlow(PrivateAccessState(readMode(), false))
    val state: StateFlow<PrivateAccessState> = mutableState.asStateFlow()
    val systemProtectionAvailable: Boolean
        get() = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun readMode(): PrivateAccessMode {
        val saved = preferences.getString("mode", null) ?: return PrivateAccessMode.UNCONFIGURED
        // A corrupt setting must never silently disable protection or allow initial setup.
        return runCatching { PrivateAccessMode.valueOf(saved) }.getOrDefault(PrivateAccessMode.PIN)
    }

    fun lock() = synchronized(monitor) {
        revision++
        mutableState.value = mutableState.value.copy(unlocked = false)
    }

    fun requireUnlocked() { check(state.value.unlocked) { "Приватный раздел заблокирован" } }

    fun unlockWithoutProtection() = synchronized(monitor) {
        if (state.value.mode == PrivateAccessMode.NONE) mutableState.value = state.value.copy(unlocked = true)
    }

    /** Call exclusively from a successful platform authentication callback. */
    fun onSystemAuthenticationSucceeded() = synchronized(monitor) {
        if (state.value.mode == PrivateAccessMode.BIOMETRIC) mutableState.value = state.value.copy(unlocked = true)
    }

    suspend fun unlockWithPin(pin: String): Boolean = withContext(Dispatchers.Default) {
        val snapshot = synchronized(monitor) {
            if (state.value.mode != PrivateAccessMode.PIN) return@withContext false
            val now = System.currentTimeMillis()
            if (preferences.getLong("blocked_until", 0L) > now) return@withContext false
            val failures = preferences.getInt("attempts", 0).coerceIn(0, 1000) + 1
            val delay = if (failures >= 5) (30_000L shl ((failures - 5) / 5).coerceAtMost(7)) else 0L
            // Charge attempts before hashing: killing the process cannot erase a failed attempt.
            persist(preferences.edit().putInt("attempts", failures).putLong("blocked_until", now + delay))
            Triple(revision, preferences.getString("salt", null), preferences.getString("hash", null))
        }
        val matches = runCatching {
            if (!validPin(pin) || snapshot.second == null || snapshot.third == null) false
            else MessageDigest.isEqual(derive(pin, Base64.getDecoder().decode(snapshot.second)), Base64.getDecoder().decode(snapshot.third))
        }.getOrDefault(false)
        synchronized(monitor) {
            if (!matches || revision != snapshot.first || state.value.mode != PrivateAccessMode.PIN) return@synchronized false
            persist(preferences.edit().remove("attempts").remove("blocked_until"))
            mutableState.value = state.value.copy(unlocked = true)
            true
        }
    }

    suspend fun configure(mode: PrivateAccessMode, pin: String? = null) = withContext(Dispatchers.Default) {
        require(mode != PrivateAccessMode.UNCONFIGURED)
        require(mode != PrivateAccessMode.PIN || (pin != null && validPin(pin))) { "Введите PIN из 6 цифр" }
        require(mode != PrivateAccessMode.BIOMETRIC || systemProtectionAvailable) { "Сначала настройте блокировку устройства" }
        val startRevision = synchronized(monitor) {
            check(state.value.mode == PrivateAccessMode.UNCONFIGURED || state.value.unlocked) { "Сначала разблокируйте раздел" }
            revision
        }
        val salt = if (mode == PrivateAccessMode.PIN) ByteArray(32).also { SecureRandom().nextBytes(it) } else null
        val hash = salt?.let { derive(requireNotNull(pin), it) }
        synchronized(monitor) {
            check(revision == startRevision) { "Раздел заблокирован; повторите вход" }
            check(state.value.mode == PrivateAccessMode.UNCONFIGURED || state.value.unlocked)
            val edit = preferences.edit().clear().putString("mode", mode.name)
            if (salt != null && hash != null) {
                edit.putString("salt", Base64.getEncoder().encodeToString(salt))
                edit.putString("hash", Base64.getEncoder().encodeToString(hash))
            }
            persist(edit)
            revision++
            mutableState.value = PrivateAccessState(mode, true)
        }
    }

    private fun persist(editor: SharedPreferences.Editor) { check(editor.commit()) { "Не удалось сохранить настройки защиты" } }
    private fun validPin(pin: String) = pin.length == 6 && pin.all { it in '0'..'9' }
    private fun derive(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    companion object {
        @Volatile private var instance: PrivateAccessManager? = null
        fun get(context: Context): PrivateAccessManager = instance ?: synchronized(this) {
            instance ?: PrivateAccessManager(context).also { instance = it }
        }
    }
}
