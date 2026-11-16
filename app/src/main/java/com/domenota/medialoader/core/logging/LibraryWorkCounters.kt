package com.domenota.medialoader.core.logging

import java.util.concurrent.atomic.AtomicLongArray

/** Fixed labels only: no file identities, titles, URIs or unbounded metric keys. */
internal enum class LibraryWork {
    COMPOSITION, MEDIASTORE_SCAN, MEDIASTORE_QUERY, THUMBNAIL_DECODE,
    BACKGROUND_PROJECTION, LIBRARY_INDEX_BUILD, COLLECTION_EMISSION, REVIEW_EMISSION,
    LISTENING_RANK_QUERY, LISTENING_STATS_QUERY, LISTENING_CACHE_HIT, RESUME_QUEUE_CAPTURE, AUDIO_QUEUE_CAPTURE, PRIVATE_QUEUE_SCAN, APP_AUDIO_IDENTITY_CACHE_HIT,
}
internal class LibraryWorkCounterSet {
    private val counts = AtomicLongArray(LibraryWork.entries.size)
    fun increment(work: LibraryWork) { counts.incrementAndGet(work.ordinal) }
    fun snapshot(): Map<LibraryWork, Long> = LibraryWork.entries.associateWith { counts.get(it.ordinal) }
}
internal object LibraryWorkCounters {
    private val counts = LibraryWorkCounterSet()
    fun increment(work: LibraryWork) = counts.increment(work)
    fun summary(): String = counts.snapshot().entries.joinToString(" ") { "${it.key.name}=${it.value}" }
}
