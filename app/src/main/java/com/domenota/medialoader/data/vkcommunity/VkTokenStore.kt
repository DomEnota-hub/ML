package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The ciphertext is private and Android backup is disabled in the manifest. */
class VkTokenStore(private val context: Context) {
    fun read(): String? {
        // A temporary Keystore failure must not erase a previously saved token.
        val value = runCatching { preferences().getString(KEY, null) }.getOrNull()
        return value?.takeIf { it.isNotBlank() }
    }

    fun save(raw: String): Boolean {
        val token = raw.trim()
        if (token.length !in 20..512 || !token.matches(Regex("[A-Za-z0-9_.-]+"))) return false
        return runCatching { preferences().edit().putString(KEY, token).commit() }
            .getOrElse {
                // The user explicitly supplied a replacement after an unreadable restore.
                context.deleteSharedPreferences(PREFS) && runCatching {
                    preferences().edit().putString(KEY, token).commit()
                }.getOrDefault(false)
            }
    }

    fun delete(): Boolean = runCatching {
        preferences().edit().remove(KEY).commit()
    }.getOrElse { context.deleteSharedPreferences(PREFS) }

    private fun preferences(): SharedPreferences = EncryptedSharedPreferences.create(
        context.applicationContext,
        PREFS,
        MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    private companion object {
        const val PREFS = "vk_community_credential"
        const val KEY = "service_token"
    }
}
