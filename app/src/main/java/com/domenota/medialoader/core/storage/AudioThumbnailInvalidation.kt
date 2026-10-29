package com.domenota.medialoader.core.storage

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Keeps the persistent audio thumbnail and visible cards in sync with MP3 rewrites. */
object AudioThumbnailInvalidation {
    private val mutableRevisions = MutableStateFlow<Map<String, Long>>(emptyMap())
    val revisions = mutableRevisions.asStateFlow()

    fun revision(savedUri: String): Long = mutableRevisions.value[savedUri] ?: 0L

    fun file(filesDir: File, savedUri: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("AUDIO|v2|$savedUri".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(filesDir, "thumbnails"), "$digest.jpg")
    }

    fun invalidate(filesDir: File, savedUri: String) {
        file(filesDir, savedUri).delete()
        mutableRevisions.update { current ->
            current + (savedUri to ((current[savedUri] ?: 0L) + 1L))
        }
    }
}
