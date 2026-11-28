package com.domenota.medialoader.data.playback

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.min

internal data class CrossfadeSettings(val enabled: Boolean = false, val seconds: Int = 5) {
    init { require(seconds in 1..12) }
}

internal class CrossfadePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("music_crossfade", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CrossfadeSettings(prefs.getBoolean("enabled", false), prefs.getInt("seconds", 5).coerceIn(1, 12)))
    val state = mutable.asStateFlow()
    fun set(value: CrossfadeSettings) {
        prefs.edit().putBoolean("enabled", value.enabled).putInt("seconds", value.seconds).apply()
        mutable.value = value
    }
    companion object {
        @Volatile private var instance: CrossfadePreferences? = null
        fun get(context: Context): CrossfadePreferences = instance ?: synchronized(this) {
            instance ?: CrossfadePreferences(context).also { instance = it }
        }
    }
}

internal object CrossfadePolicy {
    /** Duration in media time, adjusted for playback speed; leave half the track intact. */
    fun durationMs(settings: CrossfadeSettings, trackMs: Long, speed: Float): Long {
        if (!settings.enabled || trackMs <= 0 || !speed.isFinite() || speed <= 0f) return 0
        val overlap = min((settings.seconds * 1000.0 * speed).toLong(), trackMs / 2)
        return if (overlap >= (1000 * speed).toLong().coerceAtLeast(1)) overlap else 0
    }

    /** Complementary linear gains avoid adding a peak above either deck's full-scale bound. */
    fun gains(remainingMs: Long, durationMs: Long): Pair<Float, Float> {
        if (durationMs <= 0) return 1f to 0f
        val next = (1.0 - remainingMs.toDouble() / durationMs).coerceIn(0.0, 1.0).toFloat()
        return (1f - next) to next
    }
}
