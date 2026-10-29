package com.domenota.medialoader.ui.model

import com.domenota.medialoader.core.storage.StorageNaming
import com.domenota.medialoader.core.model.MediaItem

/** Names entered in the download confirmation dialog are stems, without media extensions. */
object BatchFileNames {
    fun defaults(selected: List<Pair<String, String>>): Map<String, String> =
        selected.associate { (id, originalName) -> id to StorageNaming.editableStem(originalName) }

    /** A real reset removes source captions and uses stable, readable platform names. */
    fun platformDefaults(selected: List<MediaItem>): Map<String, String> =
        selected.mapIndexed { index, item ->
            val platform = when (item.providerId.lowercase()) {
                "instagram" -> "Instagram"
                "youtube" -> "YouTube"
                "vk" -> "VK"
                "rutube" -> "RUTUBE"
                else -> "Файл"
            }
            val number = item.position?.takeIf { it > 0 }
                ?: (index + 1).takeIf { selected.size > 1 }
            item.id to if (number == null) platform else "${platform}_$number"
        }.toMap()

    fun applyStem(selected: List<Pair<String, String>>, input: String): Map<String, String> {
        val stem = input.trim()
        require(stem.isNotEmpty())
        return selected.mapIndexed { index, (id, _) ->
            id to if (selected.size == 1) stem else "${stem}_${index + 1}"
        }.toMap()
    }

    /** The common field is live; an empty value restores the source suggestions. */
    fun commonInputNames(selected: List<MediaItem>, input: String): Map<String, String> {
        val pairs = selected.map { it.id to it.originalName }
        if (input.isBlank()) return defaults(pairs)
        return applyStem(pairs, input).also { names ->
            selected.forEach { item ->
                StorageNaming.customName(names.getValue(item.id), item.originalName)
            }
        }
    }
}
