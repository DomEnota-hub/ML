package com.domenota.medialoader.core.logging

/** Each original record is owned once; grouping only references adjacent indices. */
internal object JournalTimeline {
    data class Progress(val done: Long, val total: Long) {
        val fraction: Float get() = (done.toDouble() / total.toDouble()).toFloat()
    }
    data class Row(val start: Int, val end: Int) { val count: Int get() = end - start + 1 }
    data class Snapshot(
        val raw: List<DisplayLogEntry>,
        val details: List<LogExplanation.Detail>,
        val groups: List<Row>,
        val counts: Map<LogTone, Int>,
        val genericCount: Int,
        val prefix: String,
        val preparationMs: Long,
    ) {
        fun recordText(index: Int): String = raw[index].let {
            (sequenceOf(it.original) + it.stack.asSequence()).joinToString("\n")
        }
        fun text(row: Row): String = (row.start..row.end).joinToString("\n", transform = ::recordText)
        fun select(tone: LogTone?, readable: Boolean, query: String, newestFirst: Boolean): List<Row> {
            val q = query.trim()
            val source = if (readable) groups.asSequence() else raw.indices.asSequence().map { Row(it, it) }
            val selected = source.filter { row ->
                (tone == null || raw[row.end].tone == tone) &&
                    (q.isEmpty() || (row.start..row.end).any { i ->
                        val e = raw[i]
                        e.original.contains(q, true) || e.stack.any { it.contains(q, true) } ||
                            details[i].meaning.contains(q, true) || LogDisplay.groupName(e).contains(q, true)
                    })
            }.sortedWith(compareBy<Row> { raw[it.start].timestamp }.thenBy { it.start }).toList()
            return if (newestFirst) selected.asReversed() else selected
        }
    }
    fun prepare(rawText: String): Snapshot {
        val started = System.nanoTime()
        val entries = DetailedPerformance.measure(PerfWork.LOG_PARSE) { LogDisplay.entries(rawText) }
        return DetailedPerformance.measure(PerfWork.LOG_FILTER) {
            var generic = 0
            val details = entries.map { e -> LogDisplay.specificDetail(e) ?: LogDisplay.fallback(e).also { generic++ } }
            val groups = mutableListOf<Row>()
            for (i in entries.indices) {
                val last = groups.lastOrNull()
                if (last != null && canGroup(entries[last.end], entries[i])) {
                    groups[groups.lastIndex] = Row(last.start, i)
                } else groups.add(Row(i, i))
            }
            val prefix = rawText.lineSequence().takeWhile { LogDisplay.parseHeader(it) == null }.joinToString("\n")
            Snapshot(entries, details, groups, entries.groupingBy { it.tone }.eachCount(), generic, prefix,
                (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000)
        }
    }

    private val inline = Regex("""(?:^| )([A-Za-z][A-Za-z0-9_]*)=([A-Za-z0-9_-]+)(?=$| )""")
    private val keyPattern = Regex("[A-Za-z][A-Za-z0-9_]*")
    private val identifier = Regex("[A-Za-z0-9_-]{1,128}")
    private fun fields(e: DisplayLogEntry): Map<String, String> {
        val found = mutableMapOf<String, String>()
        val duplicates = mutableSetOf<String>()
        fun add(key: String, value: String) {
            if (key in duplicates) return
            if (found.putIfAbsent(key, value) != null) { found.remove(key); duplicates.add(key) }
        }
        for (part in e.metadata.split(" · ")) {
            val key = part.substringBefore('=', "")
            if (keyPattern.matches(key)) add(key, part.substringAfter('='))
        }
        if (e.tag == "OCR" || e.tag == "DownloadProgress")
            for (m in inline.findAll(e.event)) add(m.groupValues[1], m.groupValues[2])
        return found
    }
    private fun Map<String, String>.number(key: String): Long? =
        get(key)?.takeIf { it.length <= 19 && it.all(Char::isDigit) }?.toLongOrNull()?.takeIf { it >= 0 }
    fun progress(e: DisplayLogEntry): Progress? {
        val key = when (e.tag) {
            "DownloadProgress" -> "bytes"
            "OCR" -> if (e.event.startsWith("OCR_RUN ")) "indexed" else return null
            "TaskCenter" -> "done"
            else -> return null
        }
        return progress(fields(e), key)
    }
    private fun progress(f: Map<String, String>, key: String): Progress? {
        val done = f.number(key) ?: return null
        val total = f.number("total")?.takeIf { it > 0 && it >= done } ?: return null
        return Progress(done, total)
    }
    private fun canGroup(a: DisplayLogEntry, b: DisplayLogEntry): Boolean {
        if (a.tag != b.tag ||
            a.tone != b.tone || a.technicalLevel != b.technicalLevel ||
            a.tone == LogTone.ERROR || a.tone == LogTone.WARN ||
            a.stack.any { it.isNotBlank() } || b.stack.any { it.isNotBlank() } ||
            a.timestamp.substringBefore(' ') != b.timestamp.substringBefore(' ') ||
            a.timestamp > b.timestamp) return false
        // Identical diagnostic observations can be collapsed without implying one operation.
        // Do not coalesce across hosts, threads, states, or a warning/error.
        if ((a.tag == "Perf" && a.event.startsWith("READ_MODEL_GUARD")) ||
            (a.tag == "Thumbnail" && a.event == "Fetch")) {
            return a.thread == b.thread && a.event == b.event && a.metadata == b.metadata
        }
        if (a.tag != "DownloadProgress" && a.tag != "OCR" && a.tag != "TaskCenter") return false
        val x = fields(a); val y = fields(b)
        val identityKey = when (a.tag) {
            "DownloadProgress" -> "id"
            "OCR" -> {
                if (!a.event.startsWith("OCR_RUN ") || !b.event.startsWith("OCR_RUN ") ||
                    x["phase"] != "RUNNING" || y["phase"] != "RUNNING" ||
                    x.number("failed") == null || x.number("failed") != y.number("failed") ||
                    x.number("unverified") != y.number("unverified")) return false
                "session"
            }
            "TaskCenter" -> {
                if (a.event != "CHECKPOINT" || b.event != "CHECKPOINT" || x["kind"] == null ||
                    listOf("kind", "stage", "status", "failed", "errors", "skipped", "wait", "failure").any { x[it] != y[it] }) return false
                "op"
            }
            else -> return false
        }
        val identity = x[identityKey]?.takeIf(identifier::matches) ?: return false
        if (identity != y[identityKey]) return false
        val p = progress(a) ?: return false
        val q = progress(b) ?: return false
        return p.total == q.total && p.done <= q.done
    }
}
