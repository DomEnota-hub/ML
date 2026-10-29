package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.logging.AppLog
import java.net.HttpURLConnection
import java.net.URL
import org.chromium.net.CronetEngine

/**
 * Chromium-backed HTTP transport for HitMoz links, with a single shared engine.
 *
 * A failed response (403, 405, etc.) is NEVER retried with a different client:
 * that would conceal the actual server response. A platform fallback is used only
 * when native Cronet cannot initialize on this device.
 */
internal object HitMozHttpTransport {
    @Volatile private var engine: CronetEngine? = null
    @Volatile private var unavailable = false
    private val lock = Any()

    private fun acquire(context: Context): CronetEngine? {
        engine?.let { return it }
        if (unavailable) return null
        return synchronized(lock) {
            engine ?: if (unavailable) null else try {
                CronetEngine.Builder(context.applicationContext)
                    .enableHttp2(true)
                    .enableQuic(true)
                    .build().also {
                        engine = it
                        AppLog.i("HitMoz", "Chromium Cronet MP3 transport initialized")
                    }
            } catch (error: Exception) {
                unavailable = true
                AppLog.w("HitMoz", "Cronet initialization unavailable; using platform transport", error)
                null
            }
        }
    }

    fun open(context: Context, address: URL, preferPlatform: Boolean = false): HttpURLConnection {
        val cronet = if (preferPlatform) null else acquire(context)
        val connection = if (cronet != null) {
            cronet.openConnection(address) as HttpURLConnection
        } else {
            address.openConnection() as HttpURLConnection
        }
        AppLog.verbose("HitMoz", "MP3 transport · client=${if (cronet != null) "cronet" else if (preferPlatform) "platform-retry" else "platform"}")
        return connection
    }
}
