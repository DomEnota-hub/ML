package com.domenota.medialoader.data.download

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaItem
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

internal enum class DownloadNetworkMode(val label: String) {
    ANY("Любая сеть"), WIFI("Только Wi-Fi"), LARGE_WIFI("Крупные файлы только Wi-Fi")
}

/** Marker is stored only on a QUEUED row: it is a waiting reason, not a failed transfer.
 * Keeps the existing Room schema, active counts, history and bulk cancellation semantics. */
enum class DownloadNetworkWait(val marker: String, val label: String) {
    WIFI("ML_WAIT_WIFI_V1", "Ожидание Wi-Fi"),
    INTERNET("ML_WAIT_INTERNET_V1", "Ожидание сети");
    companion object {
        fun from(state: DownloadState, marker: String?): DownloadNetworkWait? =
            if (state == DownloadState.QUEUED) entries.firstOrNull { it.marker == marker } else null
    }
}
internal val DownloadEntity.networkWait: DownloadNetworkWait?
    get() = DownloadNetworkWait.from(state, errorMessage)

internal data class DownloadNetworkPolicy(val mode: DownloadNetworkMode = DownloadNetworkMode.ANY,
    val thresholdMb: Int = 100) {
    init { require(thresholdMb in thresholds) }
    val label: String get() = if (mode == DownloadNetworkMode.LARGE_WIFI)
        "${mode.label} · от ${thresholdLabel(thresholdMb)}" else mode.label
    fun waiting(sizeBytes: Long?, internet: Boolean, wifi: Boolean, force: Boolean): DownloadNetworkWait? {
        if (!internet) return DownloadNetworkWait.INTERNET
        if (force || wifi || mode == DownloadNetworkMode.ANY) return null
        val largeOrUnknown = sizeBytes == null || sizeBytes <= 0L || sizeBytes >= thresholdMb * 1024L * 1024L
        return if (mode == DownloadNetworkMode.WIFI || largeOrUnknown) DownloadNetworkWait.WIFI else null
    }
    companion object {
        val thresholds = listOf(50, 100, 250, 500, 1024)
        fun thresholdLabel(value: Int) = if (value == 1024) "1 ГБ" else "$value МБ"
    }
}

internal class DownloadNetworkPreferences(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("download_network", Context.MODE_PRIVATE)
    private val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    fun read(): DownloadNetworkPolicy = DownloadNetworkPolicy(
        DownloadNetworkMode.entries.firstOrNull { it.name == prefs.getString("mode", null) } ?: DownloadNetworkMode.ANY,
        prefs.getInt("threshold_mb", 100).takeIf { it in DownloadNetworkPolicy.thresholds } ?: 100,
    )
    fun save(policy: DownloadNetworkPolicy) {
        prefs.edit().putString("mode", policy.mode.name).putInt("threshold_mb", policy.thresholdMb).apply()
    }
    fun waitFor(item: MediaItem, force: Boolean): DownloadNetworkWait? {
        val capabilities = runCatching { connectivity?.activeNetwork?.let { connectivity.getNetworkCapabilities(it) } }.getOrNull()
        val internet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        val wifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return read().waiting(item.sizeBytes, internet, wifi, force)
    }

    /** One shared default-network observer and preference listener, no polling per file. */
    fun changes(): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(Unit) }
            override fun onLost(network: Network) { trySend(Unit) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) { trySend(Unit) }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val registered = runCatching {
            connectivity?.registerDefaultNetworkCallback(callback)
            connectivity != null
        }.getOrDefault(false)
        trySend(Unit)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            if (registered) runCatching { connectivity?.unregisterNetworkCallback(callback) }
        }
    }.conflate()
}
