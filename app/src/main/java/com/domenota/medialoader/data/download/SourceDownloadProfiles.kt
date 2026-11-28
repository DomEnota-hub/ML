package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.preferences.VideoPreferences
import com.domenota.medialoader.data.share.InstagramShareSettings

internal enum class SourceFormat(val label: String) { VIDEO("Видео · MP4"), AUDIO("Аудио · MP3") }
internal enum class SourceDownloadMode(val label: String) { MANUAL("Ручной"), AUTOMATIC("Автоматический") }
internal data class SourceDownloadProfile(
    val height: Int? = null, // null inherits, 0 explicitly means maximum
    val direction: VideoQualityDirection? = null,
    val format: SourceFormat? = null,
    val mode: SourceDownloadMode? = null,
    val folder: String? = null,
    val confirmation: Boolean? = null,
)

/** Keep old general preferences until the owner explicitly overrides a supported feature. */
internal class SourceDownloadProfiles(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("source_download_profiles", Context.MODE_PRIVATE)
    private val general = VideoPreferences(this.context)
    fun profile(provider: String): SourceDownloadProfile {
        if (provider !in providers) return SourceDownloadProfile()
        fun value(key: String) = prefs.getString("$provider.$key", null)
        return SourceDownloadProfile(
            height = value("height")?.toIntOrNull()?.takeIf { it in heights && supportsQuality(provider) },
            direction = VideoQualityDirection.entries.firstOrNull { it.stored == value("direction") && supportsQuality(provider) },
            format = SourceFormat.entries.firstOrNull { it.name == value("format") && supportsQuality(provider) },
            mode = SourceDownloadMode.entries.firstOrNull { it.name == value("mode") && supportsAuto(provider) },
            folder = value("folder")?.takeIf(::validFolder),
            confirmation = when(value("confirmation")) { "true" -> true; "false" -> false; else -> null },
        )
    }
    fun save(provider: String, value: SourceDownloadProfile) {
        require(provider in providers)
        require(value.height == null || (supportsQuality(provider) && value.height in heights))
        require((value.format == null && value.direction == null) || supportsQuality(provider))
        require(value.mode == null || supportsAuto(provider))
        require(value.folder == null || validFolder(value.folder))
        val editor = prefs.edit()
        fun put(key: String, stored: String?) {
            if (stored == null) editor.remove("$provider.$key") else editor.putString("$provider.$key", stored)
        }
        put("height", value.height?.toString()); put("direction", value.direction?.stored)
        put("format", value.format?.name); put("mode", value.mode?.name)
        put("folder", value.folder); put("confirmation", value.confirmation?.toString())
        check(editor.commit()) { "Не удалось сохранить профиль" }
    }
    fun automatic(provider: String): Boolean {
        if (!supportsAuto(provider)) return false
        val profile = profile(provider)
        // Existing auto routes: Instagram visual publications and YouTube video only.
        if (profile.confirmation == true || profile.format == SourceFormat.AUDIO) return false
        return profile.mode?.let { it == SourceDownloadMode.AUTOMATIC }
            ?: InstagramShareSettings.isAutomatic(context)
    }
    fun skipNameConfirmation(items: List<MediaItem>): Boolean =
        items.isNotEmpty() && items.all { profile(it.providerId).confirmation == false }

    /** SAF remains the user's explicit Android grant; never silently bypass it. */
    fun folderPath(provider: String, selectedSafFolder: Boolean): String? =
        if (selectedSafFolder) null else profile(provider).folder?.let { "Pictures/$it/" }

    fun defaults(items: List<MediaItem>): List<MediaItem> {
        val updated = items.groupBy { it.providerId }.mapValues { (provider, source) ->
            val profile = profile(provider)
            val preferred = if (profile.height == null) general.preferredHeight else profile.height.takeIf { it > 0 }
            val quality = PreferredVideoQuality.apply(source, preferred, profile.direction ?: general.qualityDirection)
            // Only change initial selection for actual single-publication alternatives.
            if (!supportsQuality(provider) || quality.any { it.type == MediaType.PHOTO || (it.type == MediaType.VIDEO && it.group == null) })
                quality
            else when (profile.format) {
                SourceFormat.AUDIO -> quality.singleOrNull { it.type == MediaType.AUDIO }?.let { audio ->
                    quality.map { it.copy(preselected = it.id == audio.id) }
                } ?: quality
                SourceFormat.VIDEO -> if (quality.any { it.type == MediaType.VIDEO && it.preselected })
                    quality.map { if (it.type == MediaType.AUDIO) it.copy(preselected = false) else it }
                else quality
                null -> quality
            }
        }.mapValues { (_, source) -> source.iterator() }
        return items.map { updated.getValue(it.providerId).next() }
    }
    companion object {
        val providers = linkedMapOf("instagram" to "Instagram", "youtube" to "YouTube", "vk" to "VK", "rutube" to "Rutube")
        val heights = listOf(0) + VideoPreferences.QUALITY_OPTIONS.filterNotNull()
        fun supportsQuality(provider: String) = provider in setOf("youtube", "vk", "rutube")
        fun supportsAuto(provider: String) = provider in setOf("instagram", "youtube")
        fun validFolder(folder: String) = folder == folder.trim() && folder.isNotBlank() &&
            folder.matches(Regex("[\\p{L}\\p{N}_ -]{1,50}"))
    }
}
