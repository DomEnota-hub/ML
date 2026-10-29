package com.domenota.medialoader.core.recognition

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicLookupPolicyTest {
    private fun item(provider: String, type: MediaType, id: String, audio: Boolean? = null) =
        MediaItem(id = id, providerId = provider, type = type,
            originalName = "$id.mp4", downloadUrl = "https://example.org/test", audioAvailable = audio)

    @Test fun allFourProvidersCanOpenMusicSearch() {
        listOf("instagram", "youtube", "vk", "rutube").forEach {
            assertTrue("Expected music support for $it", MusicLookupPolicy.supports(it))
        }
        assertFalse(MusicLookupPolicy.supports("hitmoz"))
        assertFalse(MusicLookupPolicy.supports("unknown"))
        assertFalse(MusicLookupPolicy.supports(null))
    }

    @Test fun audioPreferredOverVideoForAllProviders() {
        for (provider in MusicLookupPolicy.supportedProviders) {
            val chosen = MusicLookupPolicy.preferredCandidate(listOf(
                item(provider, MediaType.VIDEO, "v"), item(provider, MediaType.AUDIO, "a"),
            ))
            assertEquals("a", chosen?.id)
        }
    }

    @Test fun videoFallbackOnlyWhenAudioTrackMayExist() {
        assertEquals("v", MusicLookupPolicy.preferredCandidate(
            listOf(item("vk", MediaType.VIDEO, "v")))?.id)
        assertNull(MusicLookupPolicy.preferredCandidate(
            listOf(item("vk", MediaType.VIDEO, "silent", audio = false))))
        assertNull(MusicLookupPolicy.preferredCandidate(
            listOf(item("unknown", MediaType.AUDIO, "a"))))
        assertNull(MusicLookupPolicy.preferredCandidate(emptyList()))
    }

    @Test fun neverMixMediaFromDifferentProviders() {
        assertNull(MusicLookupPolicy.preferredCandidate(listOf(
            item("youtube", MediaType.PHOTO, "photo"),
            item("instagram", MediaType.AUDIO, "audio"),
        )))
    }
}
