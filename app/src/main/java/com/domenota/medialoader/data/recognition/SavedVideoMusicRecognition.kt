package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import kotlinx.coroutines.CancellationException

/** Keep a failed operation distinct from a successful recognition with no match. */
internal class SavedVideoRecognitionFailure(val stage: Stage, cause: Exception) :
    Exception(stage.userMessage, cause) {
    enum class Stage(val userMessage: String) {
        AUDIO("Не удалось прочитать аудиодорожку видео. Проверьте доступ к файлу."),
        SHAZAM("Не удалось выполнить запрос распознавания Shazam. Попробуйте ещё раз."),
    }
}

enum class SavedVideoRecognitionStage { EXTRACTING_AUDIO, SEARCHING_MATCH }

internal object SavedVideoMusicRecognition {
    suspend fun recognize(savedUri: String, decode: suspend (String) -> ShortArray,
        provider: MusicRecognitionProvider,
        onStage: suspend (SavedVideoRecognitionStage) -> Unit = {},
    ): MusicRecognitionResult? {
        onStage(SavedVideoRecognitionStage.EXTRACTING_AUDIO)
        val pcm = try {
            decode(savedUri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SavedVideoRecognitionFailure(SavedVideoRecognitionFailure.Stage.AUDIO, e)
        }
        onStage(SavedVideoRecognitionStage.SEARCHING_MATCH)
        return try {
            provider.recognize(pcm)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SavedVideoRecognitionFailure(SavedVideoRecognitionFailure.Stage.SHAZAM, e)
        }
    }
}
