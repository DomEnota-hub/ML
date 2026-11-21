package com.domenota.medialoader.data.search

import android.content.Context
import android.content.SharedPreferences

/** Store only approved exact hosts; existing original URLs remain valid, never rewrite download history. */
internal object MusicOrigins {
    private const val FILE = "verified_music_origins"
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var activeHosts = emptyMap<MusicOriginProvider, String>()
    @Volatile private var previousHosts = emptyMap<MusicOriginProvider, String>()

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        activeHosts = MusicOriginProvider.entries.associateWith { provider ->
            store.getString(provider.brand + "_active", null)
                ?.let { MusicOriginPolicy.candidateHost(provider, it) } ?: provider.defaultHost
        }
        previousHosts = MusicOriginProvider.entries.mapNotNull { provider ->
            store.getString(provider.brand + "_previous", null)
                ?.let { MusicOriginPolicy.candidateHost(provider, it) }?.let { provider to it }
        }.toMap()
        prefs = store
    }

    fun host(provider: MusicOriginProvider): String = activeHosts[provider] ?: provider.defaultHost
    fun origin(provider: MusicOriginProvider): String = "https://" + host(provider)
    fun isActive(provider: MusicOriginProvider, value: String?): Boolean =
        value.equals(host(provider), ignoreCase = true)
    fun isTrusted(provider: MusicOriginProvider, value: String?): Boolean =
        value.equals(provider.defaultHost, true) || isActive(provider, value) ||
            (value != null && previousHosts[provider].equals(value, true))

    /** Called only after successful contract probe + explicit continuity/approval decision. */
    @Synchronized fun accept(proposal: MusicOriginRecovery.Proposal): Boolean {
        val provider = proposal.provider
        val confirmed = MusicOriginPolicy.candidateHost(provider, proposal.newHost) ?: return false
        val current = host(provider)
        if (current != proposal.oldHost || confirmed == current || !proposal.isFresh()) return false
        val store = prefs ?: return false
        if (!store.edit().putString(provider.brand + "_previous", current)
                .putString(provider.brand + "_active", confirmed).commit()) return false
        previousHosts = previousHosts + (provider to current)
        activeHosts = activeHosts + (provider to confirmed)
        return true
    }
}
