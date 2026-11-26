package com.domenota.medialoader.data.privatevault

import android.app.KeyguardManager
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
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

/** Process-local authorization; only settings, salted PIN hashes and throttling state are persisted. */
class PrivateAccessManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("private_access", Context.MODE_PRIVATE)
    private val monitor = Any()
    private var revision = 0L
    private val session = PrivateUnlockSession(SystemClock::elapsedRealtime)
    private val mutableDuration = MutableStateFlow(readDuration())
    val unlockDuration: StateFlow<PrivateUnlockDuration> = mutableDuration.asStateFlow()
    private val mutableState = MutableStateFlow(PrivateAccessState(readMode(), false))
    val state: StateFlow<PrivateAccessState> = mutableState.asStateFlow()
    val systemProtectionAvailable: Boolean
        get() = appContext.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true

    private fun readMode(): PrivateAccessMode {
        val saved = preferences.getString("mode", null) ?: return PrivateAccessMode.UNCONFIGURED
        // A corrupt setting must never silently disable protection or allow initial setup.
        return runCatching { PrivateAccessMode.valueOf(saved) }.getOrDefault(PrivateAccessMode.PIN)
    }

    private fun readDuration(): PrivateUnlockDuration = runCatching {
        PrivateUnlockDuration.valueOf(preferences.getString("unlock_duration", null) ?: "ON_EXIT")
    }.getOrDefault(PrivateUnlockDuration.ON_EXIT)

    fun setUnlockDuration(duration: PrivateUnlockDuration) = synchronized(monitor) {
        check(state.value.unlocked && state.value.mode in setOf(PrivateAccessMode.PIN, PrivateAccessMode.BIOMETRIC)) {
            "Сначала разблокируйте раздел"
        }
        persist(preferences.edit().putString("unlock_duration", duration.name))
        session.clear()
        mutableDuration.value = duration
    }

    fun lock() = synchronized(monitor) {
        revision++
        session.clear()
        mutableState.value = mutableState.value.copy(unlocked = false)
    }

    /** Public-to-private navigation must not mint a permit from stale live authorization. */
    fun preparePrivateEntry() = synchronized(monitor) {
        revision++
        if (state.value.unlocked) {
            session.clear()
            mutableState.value = state.value.copy(unlocked = false)
        }
    }

    /** Revoke file access immediately; only retain a re-entry permit if currently authenticated. */
    fun suspendAccess() = synchronized(monitor) {
        revision++
        val current = state.value
        if (current.mode == PrivateAccessMode.PIN || current.mode == PrivateAccessMode.BIOMETRIC)
            session.suspend(mutableDuration.value, current.unlocked)
        else session.clear()
        mutableState.value = current.copy(unlocked = false)
    }

    /** Call only from the resumed ordinary Private screen, never from settings or public routes. */
    fun resumeRememberedAccess(): Boolean = synchronized(monitor) {
        val current = state.value
        if (current.unlocked) return@synchronized true
        if (current.mode != PrivateAccessMode.PIN && current.mode != PrivateAccessMode.BIOMETRIC) {
            session.clear()
            return@synchronized false
        }
        if (current.mode == PrivateAccessMode.BIOMETRIC && !systemProtectionAvailable) {
            session.clear()
            return@synchronized false
        }
        if (!session.consume()) return@synchronized false
        revision++
        mutableState.value = current.copy(unlocked = true)
        true
    }

    fun requireUnlocked() { check(state.value.unlocked) { "Приватный раздел заблокирован" } }

    fun unlockWithoutProtection() = synchronized(monitor) {
        if (state.value.mode == PrivateAccessMode.NONE) {
            session.clear()
            mutableState.value = state.value.copy(unlocked = true)
        }
    }

    /** Call exclusively from a successful platform authentication callback. */
    fun onSystemAuthenticationSucceeded() = synchronized(monitor) {
        if (state.value.mode == PrivateAccessMode.BIOMETRIC) {
            session.clear()
            mutableState.value = state.value.copy(unlocked = true)
        }
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
            session.clear()
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
                .putString("unlock_duration", mutableDuration.value.name)
            if (salt != null && hash != null) {
                edit.putString("salt", Base64.getEncoder().encodeToString(salt))
                edit.putString("hash", Base64.getEncoder().encodeToString(hash))
            }
            persist(edit)
            revision++
            session.clear()
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
