package com.domenota.medialoader.data.catalog.visibility

/** Only a stable logical ID and a previously proved current locator can authorize
 * a rebind. Old physical locators must not become live aliases for a new file. */
internal object VisibilityReferencePolicy {
    fun currentUri(record: VisibilityMoveRecord, actual: String?): String? = when (actual) {
        record.logicalSourceUri -> record.sourceUri
        record.destinationUri -> record.destinationUri
        else -> null
    }

    fun replacementKeys(previous: Set<String>, old: Set<String>, target: Set<String>): Set<String>? {
        require(old.isNotEmpty() && target.isNotEmpty())
        if (previous.none(old::contains)) return null
        // A member with another stable app identity must not be merged with this file.
        val foreign = previous.filter { it.startsWith("app:") && it !in old && it !in target }
        check(foreign.isEmpty()) { "Коллекция содержит другую идентичность файла" }
        val stableApp = previous.any { it.startsWith("app:") && it in old }
        val oldPhysical = old.filter { it.startsWith("file-stat:") }.toSet()
        val storedPhysical = previous.filter { it.startsWith("file-stat:") }
        check(stableApp || oldPhysical.isEmpty() || storedPhysical.isEmpty() || storedPhysical.any(oldPhysical::contains)) {
            "Физическая идентичность коллекции не совпала"
        }
        return target
    }

    /** No title matching, no arbitrary merging of two listening histories. */
    fun listeningTrack(existing: Map<String,String>, old: Set<String>, target: Set<String>): String? {
        require(old.isNotEmpty() && target.isNotEmpty())
        val prior = old.mapNotNull(existing::get).toSet()
        check(prior.size <= 1) { "Неоднозначная история прослушиваний" }
        val track = prior.singleOrNull() ?: return null
        check(target.mapNotNull(existing::get).all { it == track }) { "Новый адрес уже принадлежит другой истории" }
        return track
    }
}
