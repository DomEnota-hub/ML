package com.domenota.medialoader.data.storage

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Destination for one queued transfer; never mutate shared preferences. */
internal class DownloadFolderOverride(val path: String) : AbstractCoroutineContextElement(Key) {
    init {
        require(MediaStoragePolicy.validPictures(path) &&
            path.removePrefix("Pictures/").trimEnd('/').matches(Regex("[\\p{L}\\p{N}_ -]{1,50}")))
    }
    companion object Key : CoroutineContext.Key<DownloadFolderOverride>
}
