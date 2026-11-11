package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFavoritesPolicyTest {
    private fun audio(id: String, provider: String) =
        DownloadEntity(id, provider, "$id.mp3", MediaType.AUDIO,
            DownloadState.COMPLETED, "content://media/$id", 1, 123L)

    @Test fun unifiesPreexistingPhoneAudioWithDownloadedTracks() {
        val phone = audio("device1", "device")
        val ml = audio("ml1", "youtube")
        val other = audio("other", "device")
        val rows = MusicFavoritesPolicy.select(
            listOf(phone, ml, other, phone),
            setOf("device1", "ml1"))
        assertEquals(listOf("device1", "ml1"), rows.map { it.id })
    }

    @Test fun inaccessiblePrivateAndUnmarkedMediaNeverAppear() {
        val phone = audio("device1", "device")
        val private = audio("private", "instagram").copy(isPrivate = true)
        val failed = audio("failed", "vk").copy(state = DownloadState.FAILED)
        val video = audio("video", "device").copy(mediaType = MediaType.VIDEO)
        assertEquals(listOf("device1"), MusicFavoritesPolicy.select(
            listOf(phone, private, failed, video),
            setOf("device1", "private", "failed", "video")).map { it.id })
        assertTrue(MusicFavoritesPolicy.select(listOf(phone), emptySet()).isEmpty())
    }
}
