package com.domenota.medialoader.data.storage

import com.domenota.medialoader.core.model.MediaType

/**
 * Public media placement on Android. Application albums in the Gallery are
 * virtual Room catalog records and must NEVER be converted to physical dirs.
 *
 * Android MediaStore restricts audio to Music/ (not Pictures/). Photos and
 * videos can both be stored in Pictures/MediaLoader/. Other files remain in
 * Download/ to avoid violating collection / MIME rules.
 */
internal object MediaStoragePolicy {
    const val PICTURES_ROOT = "Pictures"
    const val MUSIC_ROOT = "Music"
    const val DOWNLOAD_ROOT = "Download"
    const val DEFAULT_PICTURES_PATH = "Pictures/MediaLoader/"

    enum class Kind { IMAGE, VIDEO, AUDIO, OTHER }

    fun kind(mime: String): Kind = when {
        mime.startsWith("image/", ignoreCase = true) -> Kind.IMAGE
        mime.startsWith("video/", ignoreCase = true) -> Kind.VIDEO
        mime.startsWith("audio/", ignoreCase = true) -> Kind.AUDIO
        else -> Kind.OTHER
    }

    /**
     * Legacy history can contain GIF/HEIC/WebM/FLAC and other real formats.
     * Never relabel arbitrary bytes as JPEG/MP4/MP3 during a physical move.
     * Unknown extensions fail closed; trusted resolver MIME may identify
     * extensionless media, but must match the history media category.
     */
    fun legacyMime(type: MediaType, fileName: String, resolverMime: String?): String? {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val byExtension = when (type) {
            MediaType.PHOTO -> when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "avif" -> "image/avif"
                "bmp" -> "image/bmp"
                else -> null
            }
            MediaType.VIDEO -> when (ext) {
                "mp4", "m4v" -> "video/mp4"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "mkv" -> "video/x-matroska"
                "3gp", "3gpp" -> "video/3gpp"
                "avi" -> "video/x-msvideo"
                else -> null
            }
            MediaType.AUDIO -> when (ext) {
                "mp3" -> "audio/mpeg"
                "m4a", "mp4" -> "audio/mp4"
                "aac" -> "audio/aac"
                "flac" -> "audio/flac"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav", "wave" -> "audio/wav"
                else -> null
            }
        }
        val reported = resolverMime?.substringBefore(';')?.trim()?.lowercase()
        val requiredKind = when (type) {
            MediaType.PHOTO -> Kind.IMAGE
            MediaType.VIDEO -> Kind.VIDEO
            MediaType.AUDIO -> Kind.AUDIO
        }
        // A source labelled as a different media category must not be
        // silently rewritten just because the filename has an extension.
        if (reported != null && kind(reported) != Kind.OTHER &&
            kind(reported) != requiredKind) return null

        if (byExtension != null) return byExtension
        // Restrict MIME-only fallback to explicit types we can safely publish.
        return reported?.takeIf {
            kind(it) == requiredKind && it in setOf(
                "image/jpeg", "image/png", "image/webp", "image/gif",
                "image/heic", "image/heif", "image/avif", "image/bmp",
                "video/mp4", "video/webm", "video/quicktime",
                "video/x-matroska", "video/3gpp", "video/x-msvideo",
                "audio/mpeg", "audio/mp4", "audio/aac", "audio/flac",
                "audio/ogg", "audio/opus", "audio/wav",
            )
        }
    }

    /** Relative path includes root and trailing slash. */
    fun route(basePicturesPath: String, mime: String): String {
        require(basePicturesPath.startsWith("$PICTURES_ROOT/") &&
            basePicturesPath.endsWith('/') && ".." !in basePicturesPath &&
            '\\' !in basePicturesPath)
        val leaf = basePicturesPath.removePrefix("$PICTURES_ROOT/")
        return when (kind(mime)) {
            Kind.IMAGE, Kind.VIDEO -> basePicturesPath
            Kind.AUDIO -> "$MUSIC_ROOT/$leaf"
            Kind.OTHER -> "$DOWNLOAD_ROOT/$leaf"
        }
    }

    /** Existing Downloads preferences get a new destination, not a disk rename.
     * Historical MediaStore/content URI rows continue to refer to old files. */
    fun upgradePreference(path: String?): String {
        val value = path ?: return DEFAULT_PICTURES_PATH
        if (value.startsWith("Pictures/") && validPictures(value)) return value
        if (value.startsWith("Download/") && value.endsWith('/') &&
            ".." !in value && '\\' !in value)
            return "$PICTURES_ROOT/${value.removePrefix("Download/")}"
        return DEFAULT_PICTURES_PATH
    }

    fun validPictures(value: String) =
        value.startsWith("$PICTURES_ROOT/") && value.endsWith('/') &&
            ".." !in value && '\\' !in value &&
            value.removePrefix("$PICTURES_ROOT/").trim('/').isNotBlank()

    fun root(path: String) = path.substringBefore('/')
    fun subPath(path: String, name: String) =
        path.substringAfter('/').trimEnd('/') + "/" + name
}
