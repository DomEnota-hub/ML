package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.recognition.RecognitionAudioSpec
import com.domenota.medialoader.core.recognition.RecognitionSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShazamRecognitionProviderTest {
    private fun result(id: String, title: String = "Track") = MusicRecognitionResult(
        trackId = id,
        title = title,
        artist = "Artist",
        source = RecognitionSource.SHAZAM_DIRECT,
    )

    private fun fakeSignature(samples: ShortArray) = ShazamSignatureGenerator.Signature(
        uri = "sig-" + samples.size,
        sampleMs = samples.size * 1000L / RecognitionAudioSpec.SAMPLE_RATE_HZ,
    )

    @Test fun tooShortAudioNeverHitsTheNetwork() = runBlocking {
        var calls = 0
        val provider = ShazamRecognitionProvider(
            api = ShazamApi { calls++; result("unexpected") },
            signatureFactory = ::fakeSignature,
        )

        assertNull(provider.recognize(ShortArray(RecognitionAudioSpec.MIN_SAMPLES - 1)))
        assertEquals(0, calls)
    }

    @Test fun directMatchWinsWithoutLooping() = runBlocking {
        var calls = 0
        val provider = ShazamRecognitionProvider(
            api = ShazamApi { calls++; result("42") },
            signatureFactory = ::fakeSignature,
        )

        val recognized = provider.recognize(ShortArray(RecognitionAudioSpec.MIN_SAMPLES))

        assertEquals("42", recognized?.trackId)
        assertEquals(RecognitionSource.SHAZAM_DIRECT, recognized?.source)
        assertEquals(1, calls)
    }

    @Test fun shortClipUsesTwoLoopConfirmationsAfterDirectMiss() = runBlocking {
        val responses = ArrayDeque<MusicRecognitionResult?>().apply {
            add(null)
            add(result("77"))
            add(result("77"))
        }
        val sampleDurations = mutableListOf<Long>()
        val provider = ShazamRecognitionProvider(
            api = ShazamApi { responses.removeFirst() },
            signatureFactory = { samples ->
                fakeSignature(samples).also { sampleDurations += it.sampleMs }
            },
        )

        val recognized = provider.recognize(ShortArray(RecognitionAudioSpec.SAMPLE_RATE_HZ * 5))

        assertEquals("77", recognized?.trackId)
        assertEquals(RecognitionSource.SHAZAM_LOOP, recognized?.source)
        assertEquals(listOf(5_000L, 12_000L, 12_000L), sampleDurations)
    }

    @Test fun disagreeingLoopTrackIdsAreRejected() = runBlocking {
        val responses = ArrayDeque<MusicRecognitionResult?>().apply {
            add(null)
            add(result("first"))
            add(result("second"))
        }
        val provider = ShazamRecognitionProvider(
            api = ShazamApi { responses.removeFirst() },
            signatureFactory = ::fakeSignature,
        )

        assertNull(provider.recognize(ShortArray(RecognitionAudioSpec.SAMPLE_RATE_HZ * 6)))
    }
}
