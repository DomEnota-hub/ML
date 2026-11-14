package com.domenota.medialoader.data.library

import java.text.Normalizer
import java.util.Locale

/** Composition candidates are not claims of identical recordings. Original tags remain untouched. */
internal data class DuplicateTrack(
    val id: String, val title: String, val artist: String?, val filename: String, val uri: String,
    val bytes: Long, val modified: Long?, val sha: String, val payloadSha: String?,
    val duration: Long?, val bitrate: Long?, val folder: String?, val linked: Boolean = false,
    val current: Boolean = false, val acoustic: AcousticFingerprint? = null, val privacyEpoch: Long = 0,
)
internal enum class DuplicateKind { EXACT, ACOUSTIC, POSSIBLE }
internal data class DuplicateGroup(val tracks: List<DuplicateTrack>, val title: String, val reason: String, val kind: DuplicateKind = DuplicateKind.POSSIBLE) {
    val key: String get() = tracks.map { it.id }.sorted().joinToString("|")
}
internal object MusicDuplicatePolicy {
    private val versions = Regex("(?iu)(?:^|[\\s(\\[\\-–—_])(?:live|slowed(?:\\s*(?:and|\\+|&)\\s*reverb)?|speed[\\s_-]*up|sped[\\s_-]*up|remix|cover|acoustic|instrumental|remaster(?:ed)?|radio edit|extended(?: mix)?|nightcore|reverb|concert|кавер|ремикс|концертная(?: версия)?|акустическая(?: версия)?|инструментал|замедленная(?: версия)?|ускоренная(?: версия)?)(?=$|[\\s)\\]\\-–—_])")
    private val bracketVersion = Regex("(?iu)\\s*[\\[(][^\\])]*(?:live|slowed|speed[\\s_-]*up|sped[\\s_-]*up|remix|cover|acoustic|instrumental|remaster|radio edit|extended mix|nightcore|reverb|concert|кавер|ремикс|концерт|акустич|инструментал|замедлен|ускорен)[^\\])]*[\\])]")
    private val suffixVersion = Regex("(?iu)(?:\\s+|\\s*[-–—_:]+\\s*)(?:live|slowed(?:\\s*(?:and|\\+|&)\\s*reverb)?|speed[\\s_-]*up|sped[\\s_-]*up|remix|cover|acoustic|instrumental|remaster(?:ed)?(?:\\s+\\d{4})?|radio edit|extended(?: mix)?|nightcore|reverb|concert|кавер|ремикс|концертная версия|акустическая версия|инструментал|замедленная версия|ускоренная версия)\\s*$")
    private val extension = Regex("(?i)\\.(mp3|m4a|aac|ogg|opus|flac|wav|wma|mp4|amr|aiff)$")
    private val copySuffix = Regex("\\s*\\(\\d+\\)\\s*$")
    private fun norm(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace('ё','е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    fun artist(value: String?): String = when (val a = norm(value.orEmpty())) {
        "киш", "король и шут", "korol i shut", "korol' i shut" -> "король и шут"
        "unknown", "unknown artist", "неизвестно", "неизвестный исполнитель", "artist" -> ""
        else -> a
    }
    fun version(track: DuplicateTrack): String? = versions.find(track.title)?.value?.trim()?.trim('(', '[', '-', '–', '—', '_', ' ')?.takeIf { it.isNotBlank() }
        ?: versions.find(track.filename.removeSuffix(".mp3"))?.value?.trim()?.trim('(', '[', '-', '–', '—', '_', ' ')?.takeIf { it.isNotBlank() }
    private fun cover(track: DuplicateTrack): Boolean = Regex("(?iu)\\b(?:cover|кавер)\\b").containsMatchIn(track.title + " " + track.filename)
    private fun removeArtistAffix(value: String, candidates: Set<String>): String {
        var out = value
        for (a in candidates.filter { it.isNotBlank() }.sortedByDescending { it.length }) {
            if (out.startsWith("$a ") && out.length > a.length + 1) out = out.removePrefix("$a ").trim()
            if (out.endsWith(" $a") && out.length > a.length + 1) out = out.removeSuffix(" $a").trim()
        }
        return out
    }
    fun baseTitle(track: DuplicateTrack): String {
        var title = track.title.ifBlank { track.filename }.replace(extension, "").trim()
        // Remove only known version descriptors and duplicate-number suffixes, not arbitrary parentheses.
        repeat(4) { title = title.replace(copySuffix, "").replace(bracketVersion, "").replace(suffixVersion, "").trim() }
        val known = possibleArtists(track).flatMap { if (it == "король и шут") listOf(it,"киш","korol i shut") else listOf(it) }.toSet()
        return removeArtistAffix(norm(title), known)
    }
    private fun possibleArtists(track: DuplicateTrack): Set<String> {
        val a = artist(track.artist)
        return if (a.isNotBlank()) setOf(a) else {
            val prefix = track.filename.substringBefore(" - ", "")
            setOf(artist(prefix)).filter(String::isNotBlank).toSet()
        }
    }
    private fun sameRecording(a: DuplicateTrack, b: DuplicateTrack): Boolean =
        a.sha.isNotBlank() && a.sha == b.sha || a.payloadSha != null && a.payloadSha == b.payloadSha
    private fun candidateTitle(a: DuplicateTrack, b: DuplicateTrack): Boolean {
        val at = baseTitle(a); val bt = baseTitle(b)
        val aw = at.split(" ").filter { it.length >= 2 }.toSet()
        val bw = bt.split(" ").filter { it.length >= 2 }.toSet()
        val sharedArtist = possibleArtists(a).intersect(possibleArtists(b)).isNotEmpty()
        if (at.isBlank() || bt.isBlank()) return false
        val overlap = aw.intersect(bw)
        // A short single title is only a candidate with a known shared performer.
        return sharedArtist && overlap.isNotEmpty() &&
            ((at == bt) || (minOf(aw.size,bw.size) == 1 && (aw.all { it in bw } || bw.all { it in aw })) ||
                overlap.size >= 2 && overlap.size.toDouble()/minOf(aw.size,bw.size) >= 0.67)
    }
    fun matches(a: DuplicateTrack, b: DuplicateTrack): Boolean {
        if (sameRecording(a,b)) return true
        val at = baseTitle(a); val bt = baseTitle(b)
        val aa = possibleArtists(a); val ba = possibleArtists(b)
        val sharedArtist = aa.intersect(ba).isNotEmpty()
        val combined = (aa + ba).flatMap { if (it == "король и шут") listOf(it,"киш","korol i shut") else listOf(it) }.toSet()
        val titleMatches = at.isNotBlank() && bt.isNotBlank() &&
            (at == bt || removeArtistAffix(at, combined) == removeArtistAffix(bt, combined))
        if (!titleMatches) return candidateTitle(a,b)
        // Covers may have a different artist and every version may have a different duration.
        return sharedArtist || aa.isEmpty() || ba.isEmpty() || cover(a) || cover(b)
    }
    fun groups(input: List<DuplicateTrack>, checkActive: () -> Unit = {}): List<DuplicateGroup> {
        val rows = input.distinctBy { it.id }.sortedWith(compareByDescending<DuplicateTrack> { it.current }.thenByDescending { it.linked }.thenBy { it.id })
        // Index exact content and normalized title, then test all members before combining.
        val content = rows.groupBy { it.sha }.filterKeys { it.isNotBlank() }
        val payload = rows.filter { it.payloadSha != null }.groupBy { it.payloadSha }
        val title = rows.groupBy(::baseTitle)
        val tokens = HashMap<String,MutableList<DuplicateTrack>>()
        rows.forEach { row -> baseTitle(row).split(" ").filter { it.length >= 2 }.distinct().forEach { tokens.getOrPut(it) { mutableListOf() }.add(row) } }
        val fingerprints = rows.mapNotNull { t -> t.acoustic?.let { t.id to it } }.toMap()
        val acoustic = AcousticFingerprints.candidatePairs(fingerprints,checkActive).filter { (a,b) ->
            AcousticFingerprints.matches(fingerprints.getValue(a), fingerprints.getValue(b),checkActive)
        }.toSet()
        fun acousticMatch(a: DuplicateTrack,b: DuplicateTrack) = (minOf(a.id,b.id) to maxOf(a.id,b.id)) in acoustic
        val byId = rows.associateBy { it.id }
        val acousticNeighbors = HashMap<String,MutableSet<String>>()
        acoustic.forEach { (a,b) -> acousticNeighbors.getOrPut(a) { HashSet() }.add(b); acousticNeighbors.getOrPut(b) { HashSet() }.add(a) }
        val assigned = mutableSetOf<String>()
        val output = mutableListOf<DuplicateGroup>()
        // Strong groups are formed first. A metadata candidate must never absorb an exact group.
        for (kind in DuplicateKind.entries) for (seed in rows) {
            checkActive()
            if (seed.id in assigned) {
                if (kind == DuplicateKind.ACOUSTIC) {
                    val groupIndex = output.indexOfFirst { group -> group.kind == DuplicateKind.EXACT && group.tracks.first().id == seed.id }
                    if (groupIndex >= 0) {
                        val original = output[groupIndex]
                        val members = original.tracks.toMutableList()
                        acousticNeighbors[seed.id].orEmpty().mapNotNull { byId[it] }.filter { it.id !in assigned }.forEach { candidate ->
                            if (members.all { sameRecording(it,candidate) || acousticMatch(it,candidate) }) members += candidate
                        }
                        if (members.size > original.tracks.size) {
                            assigned += members.map { it.id }
                            output[groupIndex] = original.copy(tracks = members, kind = DuplicateKind.ACOUSTIC, reason = "Акустическое совпадение фрагментов · сравните версии")
                        }
                    }
                }
                continue
            }
            val options = linkedMapOf<String,DuplicateTrack>()
            fun add(items: List<DuplicateTrack>?) { items.orEmpty().forEach { options[it.id] = it } }
            when (kind) {
                DuplicateKind.EXACT -> { add(content[seed.sha]); add(payload[seed.payloadSha]) }
                DuplicateKind.ACOUSTIC -> add(acousticNeighbors[seed.id].orEmpty().mapNotNull { byId[it] })
                DuplicateKind.POSSIBLE -> {
                    add(title[baseTitle(seed)])
                    baseTitle(seed).split(" ").filter { it.length >= 2 }.forEach { token ->
                        // Bound extremely common metadata tokens. Exact normalized titles remain indexed.
                        tokens[token]?.takeIf { it.size <= 200 }?.let(::add)
                    }
                }
            }
            val members = mutableListOf(seed)
            for (candidate in options.values) if (candidate.id != seed.id && candidate.id !in assigned && members.all {
                when (kind) { DuplicateKind.EXACT -> sameRecording(it,candidate)
                    DuplicateKind.ACOUSTIC -> sameRecording(it,candidate) || acousticMatch(it,candidate)
                    DuplicateKind.POSSIBLE -> matches(it,candidate) }
            }) members += candidate
            if (members.size < 2) continue
            assigned += members.map { it.id }
            val reason = when (kind) {
                DuplicateKind.EXACT -> "Совпадает содержимое записи"
                DuplicateKind.ACOUSTIC -> "Акустическое совпадение фрагментов · сравните версии"
                DuplicateKind.POSSIBLE -> if (members.any { version(it) != null }) "Разные версии композиции" else "Возможные совпадения · название и исполнитель"
            }
            output += DuplicateGroup(members,seed.title,reason,kind)
        }
        return output.sortedBy { it.title.lowercase(Locale.ROOT) }
    }
    fun recommended(group: DuplicateGroup): String = group.tracks.sortedWith(
        compareByDescending<DuplicateTrack> { it.current }.thenByDescending { it.linked }
            .thenByDescending { version(it) == null }.thenByDescending { it.bitrate ?: 0 }.thenByDescending { it.duration ?: 0 }
            .thenByDescending { it.artist?.isNotBlank() == true }.thenBy { it.id }).first().id
    fun selectAll(groups: List<DuplicateGroup>, keep: Map<String,String>): Set<String> = groups.filter { it.kind == DuplicateKind.EXACT }.flatMap { group ->
        val retained = keep[group.key]?.takeIf { id -> group.tracks.any { it.id == id } } ?: recommended(group)
        group.tracks.map { it.id }.filter { it != retained }
    }.toSet()
    /** Transfer the only keeper's mark without changing unrelated/manual exceptions. */
    fun switchKeeper(group: DuplicateGroup, selected: Set<String>, previous: String, next: String): Set<String> {
        val ids = group.tracks.map { it.id }.toSet()
        if (previous == next || next !in ids || previous !in ids) return selected
        val retained = ids - selected
        val changed = selected - next
        return if (retained == setOf(previous) && next in selected) changed + previous else changed
    }
    fun validSelection(groups: List<DuplicateGroup>, selected: Set<String>): Boolean = selected.isNotEmpty() &&
        selected.all { id -> groups.any { group -> group.tracks.any { it.id == id } } } &&
        groups.all { group -> group.tracks.any { it.id !in selected } }
}
