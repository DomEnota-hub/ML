package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.*
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SavedVideoMusicRecognitionTest {
    private val noMatch = object : MusicRecognitionProvider {
        override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? = null
    }

    @Test fun unreadableVideoIsNotReportedAsMissingSongAndNeverCallsShazam() = runBlocking {
        val cause = IOException("Permission denied")
        val failure = runCatching {
            SavedVideoMusicRecognition.recognize("content://video/1", { throw cause },
                object : MusicRecognitionProvider {
                    override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? =
                        error("Network must not be used when decoding fails")
                })
        }.exceptionOrNull()
        assertTrue(failure is SavedVideoRecognitionFailure)
        assertEquals(SavedVideoRecognitionFailure.Stage.AUDIO, (failure as SavedVideoRecognitionFailure).stage)
        assertSame(cause, failure.cause)
    }

    @Test fun networkFailureRemainsDistinctFromNoMatch() = runBlocking {
        val failure = runCatching {
            SavedVideoMusicRecognition.recognize("file:///video.mp4", { shortArrayOf(1, 2) },
                object : MusicRecognitionProvider {
                    override suspend fun recognize(pcm16Mono16k: ShortArray): MusicRecognitionResult? =
                        throw IOException("Shazam HTTP 429")
                })
        }.exceptionOrNull()
        assertTrue(failure is SavedVideoRecognitionFailure)
        assertEquals(SavedVideoRecognitionFailure.Stage.SHAZAM, (failure as SavedVideoRecognitionFailure).stage)
        assertNull(SavedVideoMusicRecognition.recognize("file:///video.mp4", { shortArrayOf(1, 2) }, noMatch))
    }

    @Test fun dismissalCancelsInsteadOfBecomingRecognitionError() = runBlocking {
        val cancel = CancellationException("Dismissed")
        val failure = runCatching {
            SavedVideoMusicRecognition.recognize("content://video/1", { throw cancel }, noMatch)
        }.exceptionOrNull()
        assertSame(cancel, failure)
    }
}
