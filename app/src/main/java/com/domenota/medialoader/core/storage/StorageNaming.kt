package com.domenota.medialoader.core.storage

/** Pure file-name rules. Where files go is decided by StorageManager, not here. */
object StorageNaming {
    /** Keep the media type extension fixed while allowing a readable custom stem. */
    fun customName(input: String, originalName: String): String {
        val extension = originalName.substringAfterLast('.', "").lowercase()
        val entered = input.trim()
        val stem = if (entered.endsWith(".$extension", ignoreCase = true))
            entered.dropLast(extension.length + 1).trim() else entered
        require(stem.isNotEmpty() && stem.length <= 100 &&
            stem.none { it == '/' || it == '\\' || it == ':' || it == '*' ||
                it == '?' || it == '"' || it == '<' || it == '>' || it == '|' ||
                Character.isISOControl(it) } &&
            stem != "." && stem != "..") { "Проверьте имя файла" }
        require(extension in setOf("jpg", "jpeg", "png", "mp4", "mp3", "m4a"))
        return "$stem.$extension"
    }

    /**
     * Text shown in name editors. The extension is owned by the media type and is therefore not
     * something the user has to type or remove manually.
     */
    fun editableStem(originalName: String): String {
        val trimmed = originalName.trim()
        val dot = trimmed.lastIndexOf('.')
        return if (dot > 0 && dot < trimmed.lastIndex) trimmed.substring(0, dot) else trimmed
    }

    /** Default target relative to the public storage root. Read it only through StorageSettings. */
    const val DEFAULT_RELATIVE_DIRECTORY = "Download/MediaLoader/"

    /** Base name used when a source gives no meaningful name of its own. */
    const val FALLBACK_STEM = "Instagram"

    fun sanitizeStem(raw: String?, maxLength: Int = 80): String? {
        if (raw == null) return null
        val cleaned = raw.map { char ->
            if (char in "\\/:*?\"<>|" || Character.isISOControl(char)) ' ' else char
        }.joinToString("").replace(Regex("\\s+"), " ").trim().trim('.')
        return cleaned.take(maxLength).trim().trimEnd('.').takeIf { it.isNotEmpty() }
    }

    /** Existing names are supplied by storage; do not trust URLs as filenames. */
    fun availableName(originalName: String, existingNames: Set<String>): String {
        require(originalName.isNotBlank())
        require('/' !in originalName && '\\' !in originalName)
        if (originalName !in existingNames) return originalName
        val dot = originalName.lastIndexOf('.')
        val base = if (dot > 0) originalName.substring(0, dot) else originalName
        val extension = if (dot > 0) originalName.substring(dot) else ""
        var index = 1
        while ("${base}_${index}$extension" in existingNames) index++
        return "${base}_${index}$extension"
    }

    /**
     * Builds a file name. [carouselIndex] is 1-based and is null for single media,
     * so carousel files are numbered from the very first element: stem_1, stem_2, ...
     */
    fun mediaFileName(stem: String?, carouselIndex: Int?, extension: String): String {
        val base = stem?.takeIf { it.isNotBlank() } ?: FALLBACK_STEM
        val suffix = if (carouselIndex != null) "_$carouselIndex" else ""
        return "$base$suffix.$extension"
    }

    /** The CDN sometimes labels a generated numeric ID as filename metadata. */
    fun normalizedMediaName(name: String, carouselIndex: Int?): String {
        val extension = name.substringAfterLast('.', "jpg").lowercase()
        val stem = name.substringBeforeLast('.')
        return if (meaningfulStem(stem) == null)
            mediaFileName(null, carouselIndex, extension) else name
    }

    /** CDN paths often contain generated numeric IDs or opaque tokens; neither is an original name. */
    fun meaningfulStem(candidate: String?): String? =
        candidate?.trim()?.takeIf { stem ->
            stem.length in 1..80 &&
                stem.none { it in "\\/:*?\"<>|" || Character.isISOControl(it) } &&
                stem.any(Char::isLetter) &&
                !stem.matches(Regex("(?i)^\\d{6,}[A-Za-z0-9_-]*$")) &&
                !(stem.length > 15 && stem.count(Char::isDigit) > stem.length / 2) &&
                !stem.matches(Regex("(?i)AQ[A-Za-z0-9_-]{18,}")) &&
                !(stem.length > 24 && stem.none { it == ' ' || it == '_' || it == '-' })
        }
}
