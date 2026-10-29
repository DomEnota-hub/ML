package com.domenota.medialoader.data.playback

import android.content.Context
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

data class EqualizerSettings(val enabled: Boolean = true,
    val gainsDb: List<Float> = List(5) { 0f }, val preset: String = "Ровный") {
    init { require(gainsDb.size == 5 && gainsDb.all { it.isFinite() && it in -12f..12f }) }
    fun withBand(index: Int, gain: Float): EqualizerSettings {
        require(gain.isFinite())
        return copy(gainsDb = gainsDb.mapIndexed { n, old -> if (n == index) gain.coerceIn(-12f, 12f) else old }, preset = "Свой")
    }
    fun withPreset(name: String): EqualizerSettings =
        if (name == "Свой") copy(preset = name) else copy(gainsDb = requireNotNull(PRESETS[name]), preset = name)
    companion object {
        val FREQUENCIES = listOf(60, 230, 910, 3600, 14000)
        val PRESETS = linkedMapOf("Ровный" to listOf(0f, 0f, 0f, 0f, 0f), "Бас" to listOf(6f, 3f, 0f, -1f, 0f),
            "Вокал" to listOf(-2f, 0f, 4f, 3f, 0f), "Рок" to listOf(4f, 1f, -1f, 2f, 4f))
    }
}

/** One process-wide state shared by UI and service. Disk access never runs on the audio thread. */
class EqualizerSettingsRepository(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("audio_equalizer", 0)
    private val mutable = MutableStateFlow(EqualizerSettings())
    val settings = mutable.asStateFlow()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = scope.async { mutex.withLock { mutable.value = read() } }
    suspend fun awaitLoaded() { loaded.await() }
    suspend fun setSettings(settings: EqualizerSettings) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            // Defensive copy: callers must not mutate the snapshot consumed by the audio thread.
            val safe = settings.copy(gainsDb = settings.gainsDb.toList())
            val json = JSONObject().put("enabled", safe.enabled).put("preset", safe.preset).put("gains", JSONArray(safe.gainsDb))
            if (!preferences.edit().putString("settings", json.toString()).commit()) throw IOException("Could not save equalizer")
            mutable.value = safe
        }
    }
    private fun read(): EqualizerSettings = runCatching {
        val json = JSONObject(preferences.getString("settings", null) ?: return EqualizerSettings())
        val gains = json.getJSONArray("gains")
        EqualizerSettings(json.getBoolean("enabled"), (0..4).map { gains.getDouble(it).toFloat() }, json.getString("preset"))
    }.getOrDefault(EqualizerSettings())
    companion object {
        @Volatile private var instance: EqualizerSettingsRepository? = null
        fun get(context: Context): EqualizerSettingsRepository = instance ?: synchronized(this) {
            instance ?: EqualizerSettingsRepository(context).also { instance = it }
        }
    }
}
