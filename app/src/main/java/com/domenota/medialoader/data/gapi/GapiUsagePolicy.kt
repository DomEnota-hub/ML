package com.domenota.medialoader.data.gapi

/** Count API calls, never files in an album or downloads from the returned CDN URL. */
internal object GapiUsagePolicy {
    fun cost(path: String, status: Int): Int {
        if (status !in 200..299 && status != 404) return 0
        return if (path.substringBefore('?').trimEnd('/') == "/v2/youtube") 8 else 1
    }
}
