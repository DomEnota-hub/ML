package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity

internal enum class MusicFolderSort(val label: String) {
    NAME_ASC("По названию · А–Я"),
    NAME_DESC("По названию · Я–А"),
    COUNT_ASC("По количеству · меньше"),
    COUNT_DESC("По количеству · больше");
    companion object {
        fun fromPreference(value: String?) = entries.firstOrNull { it.name == value } ?: NAME_ASC
    }
}

internal object MusicFolderRules {
    /** Preserve the chosen order through refreshes; new entries append in original order. */
    fun visibleOrder(original: List<String>, shuffled: List<String>?): List<String> {
        val unique = original.distinct()
        if (shuffled == null) return unique
        val available = unique.toHashSet()
        val kept = shuffled.distinct().filter { it in available }
        val seen = kept.toHashSet()
        return kept + unique.filterNot { it in seen }
    }

    /** A repeated explicit shuffle must differ whenever two or more tracks are available. */
    fun shuffledOrder(current: List<String>, random: kotlin.random.Random = kotlin.random.Random.Default): List<String> {
        if (current.size < 2) return current.toList()
        val next = current.shuffled(random)
        return if (next == current) next.drop(1) + next.first() else next
    }

    fun sortedKeys(keys: Collection<String>, counts: Map<String, Int>, sort: MusicFolderSort): List<String> {
        val collator = java.text.Collator.getInstance(java.util.Locale.forLanguageTag("ru-RU")).apply {
            strength = java.text.Collator.PRIMARY
        }
        val byName = Comparator<String> { a, b ->
            collator.compare(title(a), title(b)).takeIf { it != 0 } ?: a.compareTo(b)
        }
        val comparator = when (sort) {
            MusicFolderSort.NAME_ASC -> byName
            MusicFolderSort.NAME_DESC -> byName.reversed()
            MusicFolderSort.COUNT_ASC -> compareBy<String> { counts[it] ?: 0 }.then(byName)
            MusicFolderSort.COUNT_DESC -> compareByDescending<String> { counts[it] ?: 0 }.then(byName)
        }
        return keys.sortedWith(comparator)
    }

    fun title(key: String) = key.substringAfter(':').trimEnd('/').substringAfterLast('/').ifBlank { "Корень хранилища" }
    fun key(row: DownloadEntity, deviceFolders: Map<String, String>): String? =
        row.savedUri?.let(deviceFolders::get)?.takeIf { it.isNotBlank() }
            ?: row.groupId?.takeIf { row.providerId == "device" && it.isNotBlank() }
            ?: row.savedUri?.takeIf { it.startsWith("file://") }?.removePrefix("file://")?.substringBeforeLast('/')
    fun visible(row: DownloadEntity, excluded: Set<String>, deviceFolders: Map<String, String>) =
        key(row, deviceFolders) !in excluded
}
