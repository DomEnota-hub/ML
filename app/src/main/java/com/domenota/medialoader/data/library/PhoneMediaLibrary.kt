package com.domenota.medialoader.data.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class PhoneMediaSnapshot(val items: List<DownloadEntity>, val identities: Map<String, Set<String>>,
    val fingerprints: Map<String, String> = emptyMap())

/** Ephemeral metadata only: no history imports, file reads, or media mutations. */
class PhoneMediaLibrary(context: Context) {
    private val context = context.applicationContext

    suspend fun scan(mode: AppMode): List<DownloadEntity> = scanSnapshot(mode).items

    internal suspend fun scanSnapshot(mode: AppMode, previous: PhoneMediaSnapshot? = null): PhoneMediaSnapshot = withContext(Dispatchers.IO) {
        val identities = mutableMapOf<String, Set<String>>()
        val fingerprints = mutableMapOf<String, String>()
        val types = when (mode) {
            AppMode.GALLERY -> listOf(MediaType.PHOTO, MediaType.VIDEO)
            AppMode.MUSIC -> listOf(MediaType.AUDIO)
            AppMode.DOWNLOADER -> emptyList()
        }
        val scannedAccess = mutableMapOf<MediaType, Int>()
        val items = types.flatMap { type ->
            coroutineContext.ensureActive()
            val access = accessLevel(type)
            if (access == 0) return@flatMap emptyList()
            scannedAccess[type] = access
            val uri = when (type) {
                MediaType.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val projection = mutableListOf("_id", "_display_name", "_size", "date_added", "_data", "date_modified")
            if (Build.VERSION.SDK_INT >= 30) projection += MediaStore.MediaColumns.GENERATION_MODIFIED
            if (type != MediaType.PHOTO) projection += "duration"
            if (type == MediaType.AUDIO) projection += listOf("title", "artist")
            else projection += listOf("bucket_display_name", "bucket_id")
            val rows = mutableListOf<DownloadEntity>()
            try {
                context.contentResolver.query(uri, projection.toTypedArray(), null, null, "date_added DESC, _id DESC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        coroutineContext.ensureActive()
                        val row = mapRow(cursor, type, uri)
                        val savedUri = requireNotNull(row.savedUri)
                        val path = cursor.getColumnIndex("_data").takeIf { it >= 0 && !cursor.isNull(it) }
                            ?.let(cursor::getString)?.takeIf { it.isNotBlank() }
                        fun number(name: String): Long? = cursor.getColumnIndex(name)
                            .takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
                        val modified = number("date_modified")
                        val generation = if (Build.VERSION.SDK_INT >= 30) number(MediaStore.MediaColumns.GENERATION_MODIFIED) else null
                        val fingerprint = listOf(path, row.savedUri, row.originalName, row.sizeBytes,
                            row.createdAtEpochMillis, row.groupId, row.bulkMediaKey, row.durationMs,
                            row.sourceAudioArtist, row.sourceAudioTitle, modified, generation).joinToString("\u0000")
                        fingerprints[savedUri] = fingerprint
                        val reusable = (modified != null || generation != null) && previous?.fingerprints?.get(savedUri) == fingerprint
                        identities[savedUri] = previous?.identities?.get(savedUri)?.takeIf { reusable } ?: run {
                            val identity = com.domenota.medialoader.data.playback.PublicMediaIdentity
                            val pathKeys = path?.let(identity::pathKeys).orEmpty()
                            if (pathKeys.any { it.startsWith("file-stat:") }) identity.uriKeys(savedUri) + pathKeys
                            else identity.capture(context, savedUri) + pathKeys
                        }
                        rows += row
                    }
                }
                // A revocation during a scan must not publish the rows gathered before it.
                if (accessLevel(type) >= access) rows else emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }
        }
        // Recheck each permission once after the scan, rather than a Binder check for every row.
        val allowed = scannedAccess.filter { (type, access) -> accessLevel(type) >= access }.keys
        val validated = items.filter { it.mediaType in allowed }
            .sortedWith(compareByDescending<DownloadEntity> { it.createdAtEpochMillis }.thenBy { it.id })
        val activeUris = validated.mapNotNullTo(hashSetOf()) { it.savedUri }
        PhoneMediaSnapshot(validated, identities.filterKeys { it in activeUris }, fingerprints.filterKeys { it in activeUris })
    }

    /** Database version protects against recycled IDs after a MediaStore rebuild. */
    internal suspend fun storeStamp(): PhoneStoreStamp = withContext(Dispatchers.IO) {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                val volumes = MediaStore.getExternalVolumeNames(context).sorted()
                if (volumes.isEmpty()) PhoneStoreStamp(null, null) else {
                    val versions = volumes.joinToString("|") {
                        val version = MediaStore.getVersion(context, it)
                        require(!version.isNullOrBlank())
                        "$it:$version"
                    }
                    val generation = try { volumes.joinToString("|") { "$it:${MediaStore.getGeneration(context, it)}" } }
                        catch (_: Exception) { null }
                    PhoneStoreStamp(versions, generation)
                }
            } else if (Build.VERSION.SDK_INT >= 29) PhoneStoreStamp(MediaStore.getVersion(context), null)
            else PhoneStoreStamp(null, null)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { PhoneStoreStamp(null, null) }
    }

    private fun accessLevel(type: MediaType): Int {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        val sdk = Build.VERSION.SDK_INT
        val fullPermission = when {
            sdk < 33 -> Manifest.permission.READ_EXTERNAL_STORAGE
            type == MediaType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            type == MediaType.PHOTO -> Manifest.permission.READ_MEDIA_IMAGES
            else -> Manifest.permission.READ_MEDIA_VIDEO
        }
        return when {
            granted(fullPermission) -> 2
            sdk >= 34 && type != MediaType.AUDIO && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> 1
            else -> 0
        }
    }

    companion object {
        fun permissions(mode: AppMode, sdk: Int = Build.VERSION.SDK_INT): Array<String> = when {
            mode == AppMode.DOWNLOADER -> emptyArray()
            sdk < 33 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
            mode == AppMode.MUSIC -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
            sdk >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            else -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        }

        internal fun canRead(type: MediaType, sdk: Int, granted: (String) -> Boolean): Boolean {
            if (sdk < 33) return granted(Manifest.permission.READ_EXTERNAL_STORAGE)
            val permission = when (type) {
                MediaType.PHOTO -> Manifest.permission.READ_MEDIA_IMAGES
                MediaType.VIDEO -> Manifest.permission.READ_MEDIA_VIDEO
                MediaType.AUDIO -> Manifest.permission.READ_MEDIA_AUDIO
            }
            return granted(permission) || (sdk >= 34 && type != MediaType.AUDIO && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        }

        internal fun mapRow(cursor: Cursor, type: MediaType, collection: Uri): DownloadEntity {
            fun string(name: String): String? = cursor.getColumnIndex(name).takeIf { it >= 0 && !cursor.isNull(it) }
                ?.let(cursor::getString)?.trim()?.takeIf { it.isNotEmpty() && it != "<unknown>" }
            fun number(name: String): Long? = cursor.getColumnIndex(name).takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong)
            val mediaId = requireNotNull(number("_id"))
            val kind = when (type) { MediaType.PHOTO -> "image"; MediaType.VIDEO -> "video"; MediaType.AUDIO -> "audio" }
            val uri = ContentUris.withAppendedId(collection, mediaId).toString()
            return DownloadEntity(
                id = "phone:$kind:$mediaId",
                providerId = "device",
                originalName = string("_display_name") ?: string("title") ?: "$kind-$mediaId",
                mediaType = type,
                state = DownloadState.COMPLETED,
                savedUri = uri,
                sizeBytes = number("_size")?.takeIf { it >= 0 },
                createdAtEpochMillis = (number("date_added") ?: 0L) * 1000,
                previewUrl = if (type != MediaType.AUDIO) uri else null,
                groupId = if (type != MediaType.AUDIO) string("bucket_display_name") else null,
                bulkMediaKey = if (type != MediaType.AUDIO) number("bucket_id")?.toString() else null,
                sourceAudioArtist = if (type == MediaType.AUDIO) string("artist") else null,
                sourceAudioTitle = if (type == MediaType.AUDIO) string("title") else null,
                durationMs = if (type != MediaType.PHOTO) number("duration")?.takeIf { it >= 0 } else null,
            )
        }
    }
}
