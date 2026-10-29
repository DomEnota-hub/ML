package com.domenota.medialoader.data.usecase

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.data.MediaRepository

/** Keeps provider and download details out of the UI layer. */
class AnalyzeMedia(private val repository: MediaRepository) {
    suspend operator fun invoke(url: String): List<MediaItem> = repository.resolve(url)
}

class QueueMedia(private val repository: MediaRepository) {
    suspend operator fun invoke(items: List<MediaItem>) = repository.enqueue(items)
}
