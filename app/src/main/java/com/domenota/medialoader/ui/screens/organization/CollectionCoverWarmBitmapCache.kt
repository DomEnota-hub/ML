package com.domenota.medialoader.ui.screens.organization

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.max
import kotlin.math.roundToInt

/** Small, transient display-only preview for a previously rendered *public*
 * automatic collection cover. This does NOT grant access to any source:
 * CollectionCoverImage must revalidate its slot, identity and permission before
 * calling CollectionOriginalCover. A different stable identity cannot reuse it.
 * It never writes to disk or makes media visible to MediaStore.
 */
internal object CollectionCoverWarmBitmapCache {
    private const val CAP_BYTES = 16 * 1024 * 1024
    private const val PREVIEW_LONG_SIDE = 256
    private val cache = object : LruCache<String, Bitmap>(CAP_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun peek(identity: String): Bitmap? = synchronized(cache) {
        cache.get(identity)?.takeUnless { it.isRecycled }
    }
    fun remember(identity: String, image: Bitmap) {
        require(identity.isNotBlank() && identity.length <= 256)
        if (image.isRecycled || image.width <= 0 || image.height <= 0) return
        val longSide = max(image.width,image.height)
        val preview = if (longSide <= PREVIEW_LONG_SIDE) image else {
            val ratio = PREVIEW_LONG_SIDE.toFloat() / longSide
            Bitmap.createScaledBitmap(image,
                (image.width * ratio).roundToInt().coerceAtLeast(1),
                (image.height * ratio).roundToInt().coerceAtLeast(1),true)
        }
        synchronized(cache) { cache.put(identity,preview) }
    }
    fun forget(identity:String) { synchronized(cache) { cache.remove(identity) } }
    fun clear() { synchronized(cache) { cache.evictAll() } }
}
