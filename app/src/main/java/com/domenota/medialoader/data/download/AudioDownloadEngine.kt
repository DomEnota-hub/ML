package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.model.DownloadTask
import kotlinx.coroutines.CancellationException

/** AUDIO tasks: download the source media and encode its audio track as MP3. */
class AudioDownloadEngine(private val extractor: AudioExtractor) : DownloadEngine {
    override suspend fun download(
        task: DownloadTask,
        onProgress: suspend (bytesDownloaded: Long, totalBytes: Long?) -> Unit,
    ): DownloadResult = try {
        val saved = extractor.saveAudio(task.item.downloadUrl, task.fileName, onProgress)
        DownloadResult(saved.published.uri.toString(), saved.sizeBytes, saved.published.fileName)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: AudioExtractor.NoAudioTrackException) {
        throw DownloadFailure(error.message ?: "В этом видео нет звуковой дорожки.", error)
    } catch (error: AudioExtractor.VideoTooLargeException) {
        throw DownloadFailure(error.message ?: "Видео слишком большое для извлечения аудио.", error)
    } catch (error: AudioExtractor.InsufficientStorageException) {
        throw DownloadFailure(error.message ?: "Недостаточно места на устройстве.", error)
    } catch (error: LinkageError) {
        throw DownloadFailure("Не удалось запустить обработку MP3. Обновите приложение.", error)
    } catch (error: Exception) {
        throw DownloadFailure("Не удалось извлечь аудио. Повторите попытку.", error)
    }
}
