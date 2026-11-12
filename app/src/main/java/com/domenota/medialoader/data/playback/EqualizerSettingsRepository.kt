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
    private val profiles = MutableStateFlow(EqualizerProfileState())
    internal val state = profiles.asStateFlow()
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = scope.async { mutex.withLock { publish(read()) } }
    suspend fun awaitLoaded() { loaded.await() }
    suspend fun setSettings(settings: EqualizerSettings) = save(null, settings)
    internal suspend fun setTrackSettings(track: EqualizerTrack, settings: EqualizerSettings?) = save(track, settings)

    private suspend fun save(track: EqualizerTrack?, settings: EqualizerSettings?) = withContext(Dispatchers.IO) {
        loaded.await()
        mutex.withLock {
            val next = profiles.value.changed(track, settings)
            if (next == profiles.value) return@withLock
            val records = JSONArray()
            next.tracks.forEach { profile ->
                records.put(JSONObject().put("keys", JSONArray(profile.keys.sorted()))
                    .put("settings", encode(profile.settings)))
            }
            // Preserve the previous global settings key and format so upgrades are lossless.
            // Write the global and track records together, then publish a single snapshot.
            if (!preferences.edit().putString("settings", encode(next.global).toString())
                    .putString("track_profiles_v1", records.toString()).commit()) {
                throw IOException("Could not save equalizer")
            }
            publish(next)
        }
    }

    private fun publish(value: EqualizerProfileState) {
        profiles.value = value
        mutable.value = value.global
    }
    private fun encode(settings: EqualizerSettings) = JSONObject().put("enabled", settings.enabled)
        .put("preset", settings.preset).put("gains", JSONArray(settings.gainsDb))
    private fun decode(json: JSONObject): EqualizerSettings {
        val gains = json.getJSONArray("gains")
        require(gains.length() == 5)
        return EqualizerSettings(json.getBoolean("enabled"), (0..4).map { gains.getDouble(it).toFloat() }, json.getString("preset"))
    }
    private fun read(): EqualizerProfileState {
        val global = runCatching { decode(JSONObject(preferences.getString("settings", null) ?: "{}")) }
            .getOrDefault(EqualizerSettings())
        val records = runCatching { JSONArray(preferences.getString("track_profiles_v1", null) ?: "[]") }
            .getOrDefault(JSONArray())
        val tracks = (0 until records.length()).mapNotNull { index ->
            runCatching {
                val record = records.getJSONObject(index)
                val array = record.getJSONArray("keys")
                val keys = (0 until array.length()).map { array.getString(it) }.filter(String::isNotBlank).toSet()
                require(keys.isNotEmpty())
                EqualizerTrackProfile(keys, decode(record.getJSONObject("settings")))
            }.getOrNull()
        }
        return EqualizerProfileState(global, tracks)
    }
    companion object {
        @Volatile private var instance: EqualizerSettingsRepository? = null
        fun get(context: Context): EqualizerSettingsRepository = instance ?: synchronized(this) {
            instance ?: EqualizerSettingsRepository(context).also { instance = it }
        }
    }
}
