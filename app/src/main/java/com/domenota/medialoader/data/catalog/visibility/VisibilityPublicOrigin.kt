package com.domenota.medialoader.data.catalog.visibility

/** Original public placement is primary recovery metadata, not a cache. DATE_ADDED
 * is system-owned; preserve it in ML's logical record even if Android assigns a new value. */
internal data class VisibilityPublicOrigin(
    val uri: String,
    val volume: String,
    val relativePath: String,
    val name: String,
    val mime: String,
    val physicalPath: String,
    val bytes: Long,
    val modifiedSeconds: Long,
    val addedSeconds: Long,
    val takenMillis: Long?,
    val owner: String?,
) {
    init {
        require(uri.startsWith("content://media/") && volume.matches(Regex("[A-Za-z0-9_-]+")) && volume != "external")
        require(relativePath.endsWith('/') && !relativePath.startsWith('/') &&
            relativePath.removeSuffix("/").split('/').all { it.isNotBlank() && !it.startsWith('.') && '\\' !in it && it.none(Char::isISOControl) })
        require(name.isNotBlank() && !name.startsWith('.') && name.none { it == '/' || it == '\\' || it.isISOControl() })
        require(mime.substringBefore('/') in setOf("image", "video", "audio") && mime.substringAfter('/', "").isNotBlank())
        require(physicalPath.startsWith('/') && physicalPath.endsWith("/$relativePath$name") &&
            physicalPath.split('/').none { it == "." || it == ".." })
        require(bytes > 0 && modifiedSeconds > 0 && modifiedSeconds <= Long.MAX_VALUE / 1000 && addedSeconds >= 0)
        require(takenMillis == null || takenMillis > 0)
    }
    val modifiedMillis get() = modifiedSeconds * 1000L

    /** A unique, valid intermediate name; reserve it before writing anything. */
    fun reservationName(token: String): String {
        require(token.matches(Regex("[A-Za-z0-9_-]{16,100}")))
        val suffix = name.substringAfterLast('.', "").takeIf { it.matches(Regex("[A-Za-z0-9]{1,10}")) }
        return "ML-visibility-$token" + if (suffix != null) ".$suffix" else ""
    }
}
