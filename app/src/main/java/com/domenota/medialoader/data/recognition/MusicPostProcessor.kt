package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.recognition.MusicLookupPolicy
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.core.recognition.MusicRecognitionMode
import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.data.HistoryRepository
import com.domenota.medialoader.data.storage.StorageManager
import java.io.File
import kotlinx.coroutines.CancellationException

class MusicPostProcessor(
    private val storage: StorageManager,
    private val history: HistoryRepository,
    private val settings: MusicRecognitionSettings,
    private val recognizer: InstagramMusicRecognizer,
    private val preprocessor: RecognitionAudioPreprocessor,
    private val metadataWriter: Mp3MetadataWriter,
) {
    suspend fun processDownloaded(
        id: String,
        item: MediaItem,
    ): MusicRecognitionResult? {
        if (settings.mode != MusicRecognitionMode.AUTO) return null
        if (item.type != MediaType.AUDIO) return null
        return process(id, item, preferAcoustic = true)
    }

    suspend fun processExisting(id: String, manualAttempt: Int = 0): MusicRecognitionResult? {
        val row = history.byId(id) ?: return null
        if (!eligible(row)) return null
        val item = MediaItem(
            id = row.id,
            providerId = row.providerId,
            type = row.mediaType,
            originalName = row.originalName,
            downloadUrl = row.sourceUrl.orEmpty(),
            previewUrl = row.previewUrl,
            audioArtist = row.sourceAudioArtist,
            audioTitle = row.sourceAudioTitle,
            audioArtworkUrl = row.sourceAudioArtworkUrl,
        )
        return process(id, item, manualAttempt)
    }

    /** Apply only after the user confirms a different result from a later audio window. */
    suspend fun applyProposed(id: String, confirmed: MusicRecognitionResult): MusicRecognitionResult? {
        val row = history.byId(id) ?: return null
        if (!eligible(row)) return null
        val item = MediaItem(
            id = row.id,
            providerId = row.providerId,
            type = row.mediaType,
            originalName = row.originalName,
            downloadUrl = row.sourceUrl.orEmpty(),
            previewUrl = row.previewUrl,
            audioArtist = row.sourceAudioArtist,
            audioTitle = row.sourceAudioTitle,
            audioArtworkUrl = row.sourceAudioArtworkUrl,
        )
        return process(id, item, confirmedResult = confirmed)
    }

    private suspend fun process(
        id: String,
        item: MediaItem,
        manualAttempt: Int? = null,
        confirmedResult: MusicRecognitionResult? = null,
        preferAcoustic: Boolean = false,
    ): MusicRecognitionResult? {
        var sourceFile: File? = null
        var taggedFile: File? = null
        try {
            val initial = history.byId(id) ?: return null
            if (!eligible(initial)) return null
            val savedUri = initial.savedUri ?: return null

            // Explicit re-recognition must use audio, not repeat existing source metadata.
            val sourceMetadata = if (!preferAcoustic && manualAttempt == null && confirmedResult == null) recognizer.sourceMetadata(item) else null
            if (sourceMetadata == null && confirmedResult == null) {
                sourceFile = storage.copyDownloadToTemp(savedUri, ".mp3")
            }

            val acousticItem = if (manualAttempt != null) item.copy(
                audioArtist = null, audioTitle = null, audioArtworkUrl = null,
            ) else item
            val result = confirmedResult ?: sourceMetadata ?: recognizer.recognize(acousticItem, preferAcoustic = preferAcoustic) {
                preprocessor.decode(requireNotNull(sourceFile), manualAttempt ?: 0)
            } ?: run {
                AppLog.i("Recognition", "No reliable match · id=$id · provider=${item.providerId}")
                return null
            }

            if (manualAttempt != null && !initial.recognizedTitle.isNullOrBlank() &&
                !initial.recognizedArtist.isNullOrBlank() &&
                (!initial.recognizedTitle.equals(result.title, ignoreCase = true) ||
                    !initial.recognizedArtist.equals(result.artist, ignoreCase = true))) {
                // Avoid silently replacing known metadata with a potentially incorrect Shazam match.
                AppLog.i("Recognition", "Alternative match retained for user review · id=${id} · attempt=${manualAttempt}")
                return result
            }

            val beforeWrite = history.byId(id) ?: return null
            if (!eligible(beforeWrite) || beforeWrite.savedUri != savedUri) return null

            if (sourceFile == null) {
                sourceFile = storage.copyDownloadToTemp(savedUri, ".mp3")
            }
            taggedFile = metadataWriter.write(requireNotNull(sourceFile), result)
            if (!storage.replaceDownload(savedUri, requireNotNull(taggedFile))) {
                throw IllegalStateException("Не удалось обновить MP3.")
            }
            storage.invalidateAudioThumbnail(savedUri)

            var finalUri = savedUri
            var finalName = beforeWrite.originalName
            if (settings.renameRecognized) {
                val artist = StorageNaming.sanitizeStem(result.artist, 45)
                val title = StorageNaming.sanitizeStem(result.title, 55)
                if (artist != null && title != null) {
                    val preferred = StorageNaming.mediaFileName("$artist - $title", null, "mp3")
                    val existing = storage.existingNames() - beforeWrite.originalName
                    val available = StorageNaming.availableName(preferred, existing)
                    if (available != beforeWrite.originalName) {
                        storage.renameDownload(savedUri, available)?.let { renamedUri ->
                            finalUri = renamedUri
                            finalName = available
                            if (finalUri != savedUri) storage.invalidateAudioThumbnail(finalUri)
                        }
                    }
                }
            }

            val latest = history.byId(id) ?: return result
            if (!latest.hidden) {
                history.save(
                    latest.copy(
                        originalName = finalName,
                        savedUri = finalUri,
                        sizeBytes = taggedFile.length(),
                        recognizedArtist = result.artist,
                        recognizedTitle = result.title,
                        recognizedAlbum = result.album,
                        recognitionTrackId = result.trackId,
                        recognitionSource = result.source.name,
                        recognitionArtworkUrl = result.artworkUrl,
                    ),
                )
            }
            AppLog.i(
                "Recognition",
                "Applied · id=$id · source=${result.source} · trackId=${result.trackId ?: "-"} · " +
                    "artist=${result.artist} · title=${result.title}",
            )
            return result
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("Recognition", "Post-processing failed · id=$id", error)
            return null
        } finally {
            sourceFile?.delete()
            taggedFile?.delete()
        }
    }

    private fun eligible(row: DownloadEntity): Boolean =
        row.state == DownloadState.COMPLETED &&
            !row.hidden &&
            row.mediaType == MediaType.AUDIO &&
            !row.savedUri.isNullOrBlank()
}
