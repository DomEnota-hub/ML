package com.domenota.medialoader.data.storage

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
