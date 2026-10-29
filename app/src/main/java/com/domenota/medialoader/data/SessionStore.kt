package com.domenota.medialoader.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Instagram session cookies, encrypted with a key from Android Keystore.
 * The app never sees the account password: the user signs in inside a WebView.
 */
class SessionStore(context: Context) {
    private val prefs: SharedPreferences? = runCatching {
        EncryptedSharedPreferences.create(
            context.applicationContext, "sessions",
            MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    @Volatile
    private var memoryOnly: String? = null // fallback if Keystore is unavailable on a device
    @Volatile
    private var workingSessionFingerprint: Int? = null

    fun cookies(): String? = (prefs?.getString(KEY, null) ?: memoryOnly)?.takeIf { it.isNotBlank() }

    fun isLoggedIn(): Boolean = cookies()?.let(InstagramSessionCookiePolicy::hasSession) == true

    private fun sessionFingerprint(): Int? = cookies()?.split(';')?.firstOrNull {
        it.trim().startsWith("sessionid=")
    }?.trim()?.substringAfter('=')?.takeIf(String::isNotBlank)?.hashCode()

    /** Other Instagram cookies rotate; only the login token identifies the current session. */
    fun hasWorkingMediaSession(): Boolean = isLoggedIn() &&
        sessionFingerprint()?.let { fingerprint ->
            workingSessionFingerprint == fingerprint ||
                prefs?.getInt(VERIFIED_COOKIE_HASH, 0) == fingerprint
        } == true

    fun markWorkingMediaSession() {
        sessionFingerprint()?.let {
            workingSessionFingerprint = it
            prefs?.edit()?.putInt(VERIFIED_COOKIE_HASH, it)?.apply()
        }
    }

    /** Use the browser's complete cookie header after its Instagram page is available. */
    fun syncFromWebView(): Boolean {
        val manager = CookieManager.getInstance()
        val browser = listOf("https://www.instagram.com", "https://instagram.com")
            .mapNotNull(manager::getCookie)
            .firstOrNull(InstagramSessionCookiePolicy::hasSession) ?: return false
        if (browser != cookies()) save(browser)
        return true
    }

    fun save(cookies: String) {
        memoryOnly = cookies
        prefs?.edit()?.putString(KEY, cookies)?.apply()
    }

    /** A manually supplied session token is a credential. Never display or log it. */
    fun saveManualSessionId(input: String) {
        val token = input.trim().removePrefix("sessionid=")
        require(token.matches(Regex("[A-Za-z0-9._~%:+/=-]{10,4096}")))
        save("sessionid=$token")
    }

    fun clear() {
        memoryOnly = null
        workingSessionFingerprint = null
        prefs?.edit()?.remove(KEY)?.remove(VERIFIED_COOKIE_HASH)?.apply()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private companion object {
        const val KEY = "instagram_cookies"
        const val VERIFIED_COOKIE_HASH = "instagram_working_cookie_hash"
    }
}
