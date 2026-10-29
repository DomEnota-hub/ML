package com.domenota.medialoader.data.search

/** Experimental per-full-song-source selection. No global HTTP or VPN changes. */
enum class FullSongSource(
    val storedValue: String,
    val displayName: String,
    val providerId: String,
) {
    AUTO("auto", "Автоматически", ""),
    HITMOZ("hitmoz", "HitMoz", "hitmoz"),
    MUZMO("muzmo", "Muzmo", "muzmo");

    companion object {
        const val PREF_KEY = "full_song_source"
        fun fromStored(value: String?): FullSongSource =
            entries.firstOrNull { it.storedValue == value } ?: HITMOZ
    }
}
