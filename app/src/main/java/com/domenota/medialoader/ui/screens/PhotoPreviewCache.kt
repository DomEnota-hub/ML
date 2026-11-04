package com.domenota.medialoader.ui.screens

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Viewer-owned small previews; never persist private photo pixels across viewer sessions. */
internal class PhotoPreviewCache(maxBytes: Int = 24 * 1024 * 1024) {
    private val cache = object : android.util.LruCache<String, DecodedImage>(maxBytes) {
        override fun sizeOf(key: String, value: DecodedImage) = value.bitmap.allocationByteCount
    }
    fun get(key: String): DecodedImage? = cache.get(key)
    fun put(key: String, image: DecodedImage) { cache.put(key, image) }
    fun clear() { cache.evictAll() } // Displayed images may still reference an evicted bitmap.
}
private val photoDecodeGate = Semaphore(2)
internal suspend fun viewerPhotoDecode(context: Context, uri: Uri, maxSide: Int): DecodedImage? =
    photoDecodeGate.withPermit { decodeLocalImage(context, uri, maxSide) }
