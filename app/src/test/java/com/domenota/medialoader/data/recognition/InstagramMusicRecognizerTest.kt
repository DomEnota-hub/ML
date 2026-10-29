package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class InstagramMusicRecognizerTest {
    @Test fun verifiedYoutubeMusicMetadataSkipsAcousticDownload() = runBlocking {
        val recognizer = InstagramMusicRecognizer(object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? =
                error("Acoustic recognition should not start")
        })
        val item = MediaItem("video", "youtube", MediaType.AUDIO, "Artist - Song.mp3",
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ", audioArtist = "Artist", audioTitle = "Song")
        val result = recognizer.recognize(item) { error("PCM should not load") }
        assertEquals("Artist", result?.artist)
        assertEquals("Song", result?.title)
    }
    @Test fun completeInstagramMetadataSkipsPcmAndAcousticRecognition() = runBlocking {
        var pcmLoads = 0
        var acousticCalls = 0
        val acoustic = object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? {
                acousticCalls++
                return null
            }
        }
        val recognizer = InstagramMusicRecognizer(acoustic)
        val item = MediaItem(
            id = "music",
            providerId = "instagram",
            type = MediaType.AUDIO,
            originalName = "Artist - Song.mp3",
            downloadUrl = "https://video.xx.fbcdn.net/song.m4a",
            audioArtist = "Artist",
            audioTitle = "Song",
            audioArtworkUrl = "https://scontent.cdninstagram.com/music-cover.jpg",
        )

        val result = recognizer.recognize(item) {
            pcmLoads++
            ShortArray(0)
        }

        assertEquals("Artist", result?.artist)
        assertEquals("Song", result?.title)
        assertEquals("https://scontent.cdninstagram.com/music-cover.jpg", result?.artworkUrl)
        assertEquals(RecognitionSource.SOURCE_METADATA, result?.source)
        assertEquals(0, pcmLoads)
        assertEquals(0, acousticCalls)
    }

    @Test fun incompleteInstagramMetadataLoadsPcmAndFallsBackToAcousticRecognition() = runBlocking {
        var pcmLoads = 0
        var acousticCalls = 0
        val acoustic = object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? {
                acousticCalls++
                return MusicRecognitionResult(
                    trackId = "55",
                    title = "Found Song",
                    artist = "Found Artist",
                    source = RecognitionSource.SHAZAM_DIRECT,
                )
            }
        }
        val recognizer = InstagramMusicRecognizer(acoustic)
        val item = MediaItem(
            id = "music",
            providerId = "instagram",
            type = MediaType.AUDIO,
            originalName = "Instagram.mp3",
            downloadUrl = "https://video.xx.fbcdn.net/song.m4a",
            audioArtist = "Artist only",
        )

        val result = recognizer.recognize(item) {
            pcmLoads++
            ShortArray(64_000)
        }

        assertEquals("55", result?.trackId)
        assertEquals(1, pcmLoads)
        assertEquals(1, acousticCalls)
    }
    @Test fun genericInstagramOriginalAudioFallsBackToShazam() = runBlocking {
        var pcmLoads = 0
        var acousticCalls = 0
        val acoustic = object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? {
                acousticCalls++
                return MusicRecognitionResult(
                    trackId = "99",
                    title = "Real Song",
                    artist = "Real Artist",
                    source = RecognitionSource.SHAZAM_DIRECT,
                )
            }
        }
        val recognizer = InstagramMusicRecognizer(acoustic)
        val item = MediaItem(
            id = "music",
            providerId = "instagram",
            type = MediaType.AUDIO,
            originalName = "user - Original audio.mp3",
            downloadUrl = "https://video.xx.fbcdn.net/song.m4a",
            audioArtist = "user",
            audioTitle = "Original audio",
        )

        val result = recognizer.recognize(item) {
            pcmLoads++
            ShortArray(64_000)
        }

        assertEquals("99", result?.trackId)
        assertEquals(1, pcmLoads)
        assertEquals(1, acousticCalls)
    }

    private fun namedTrack(provider: String = "instagram") = MediaItem(
        "song", provider, MediaType.AUDIO, "Artist - Song.mp3", "https://example.org/song",
        audioArtist = "Artist", audioTitle = "Song", audioArtworkUrl = "https://example.org/old.jpg")

    @Test fun downloadedMusicChecksShazamEvenWithCompleteMetadata() = runBlocking {
        val recognizer = InstagramMusicRecognizer(object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray) = MusicRecognitionResult(
                trackId = "shazam", title = "Song", artist = "Artist",
                artworkUrl = "https://example.org/shazam.jpg", source = RecognitionSource.SHAZAM_DIRECT)
        })
        val result = recognizer.recognize(namedTrack(), preferAcoustic = true) { ShortArray(64000) }
        assertEquals("shazam", result?.trackId)
        assertEquals("https://example.org/shazam.jpg", result?.artworkUrl)
        assertEquals(RecognitionSource.SHAZAM_DIRECT, result?.source)
    }

    @Test fun noAcousticMatchKeepsSourceMetadata() = runBlocking {
        val recognizer = InstagramMusicRecognizer(object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? = null
        })
        val result = recognizer.recognize(namedTrack(), preferAcoustic = true) { ShortArray(64000) }
        assertEquals("Song", result?.title)
        assertEquals("https://example.org/old.jpg", result?.artworkUrl)
    }

    @Test fun shazamNetworkFailureKeepsMuzmoMetadata() = runBlocking {
        val recognizer = InstagramMusicRecognizer(object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? =
                throw java.net.UnknownHostException("shazam")
        })
        val result = recognizer.recognize(namedTrack("muzmo"), preferAcoustic = true) { ShortArray(64000) }
        assertEquals("Song", result?.title)
        assertEquals("https://example.org/old.jpg", result?.artworkUrl)
    }

    @Test fun acousticFirstDoesNotSwallowCancellation() = runBlocking {
        val recognizer = InstagramMusicRecognizer(object : MusicRecognitionProvider {
            override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? =
                throw kotlinx.coroutines.CancellationException("stop")
        })
        try {
            recognizer.recognize(namedTrack(), preferAcoustic = true) { ShortArray(64000) }
            org.junit.Assert.fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) { }
    }

}
