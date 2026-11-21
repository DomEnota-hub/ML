package com.domenota.medialoader.core.model

enum class VideoQualityDirection(val stored: String, val label: String) {
    LOWER("lower", "Ниже"), HIGHER("higher", "Выше");

    companion object {
        fun fromStored(value: String?): VideoQualityDirection =
            entries.firstOrNull { it.stored == value } ?: LOWER
    }
}

/** Shared default choice; explicit user selection and bulk rules remain independent. */
object PreferredVideoQuality {
    private val supported = setOf("youtube", "vk", "rutube")
    private val heightPattern = Regex("^\\s*(\\d{2,5})p", RegexOption.IGNORE_CASE)
    fun apply(items: List<MediaItem>, preferredHeight: Int?, direction: VideoQualityDirection = VideoQualityDirection.LOWER): List<MediaItem> {
        val choices = items.filter { it.type == MediaType.VIDEO && it.providerId in supported }
            .groupBy { Triple(it.providerId, it.sourcePageUrl ?: it.downloadUrl, it.group) }
            .mapValues { (_, alternatives) ->
                val known = alternatives.mapNotNull { item ->
                    heightPattern.find(item.qualityLabel.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
                        ?.takeIf { it > 0 }?.let { item to it }
                }
                // Unspecified labels such as "Лучшее" retain the provider's original choice.
                if (known.isEmpty()) null
                else {
                    val chosen = if (preferredHeight == null) known.maxBy { it.second }
                        else when (direction) {
                            VideoQualityDirection.LOWER -> known.filter { it.second <= preferredHeight }
                                .maxByOrNull { it.second } ?: known.minBy { it.second }
                            VideoQualityDirection.HIGHER -> known.filter { it.second >= preferredHeight }
                                .minByOrNull { it.second } ?: known.maxBy { it.second }
                        }
                    chosen.first.id
                }
            }.filterValues { it != null }
        if (choices.isEmpty()) return items
        return items.map { item ->
            val key = Triple(item.providerId, item.sourcePageUrl ?: item.downloadUrl, item.group)
            if (item.type == MediaType.VIDEO && key in choices)
                item.copy(preselected = item.id == choices[key])
            else item
        }
    }
}
