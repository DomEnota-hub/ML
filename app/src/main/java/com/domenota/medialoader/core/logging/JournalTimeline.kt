package com.domenota.medialoader.core.logging

/** Each original record is owned once; grouping only references adjacent indices. */
internal object JournalTimeline {
    data class Progress(val done: Long, val total: Long) {
        val fraction: Float get() = (done.toDouble() / total.toDouble()).toFloat()
    }
    data class Row(val start: Int, val end: Int) { val count: Int get() = end - start + 1 }
    enum class Scope(val label:String) { ALL("Весь журнал"), VERSION("Текущая версия"), SESSION("Текущий сеанс") }
    data class Session(val start:Int,val end:Int,val version:String?)
    data class Stats(val total:Int,val counts:Map<LogTone,Int>)
    data class Snapshot(
        val raw: List<DisplayLogEntry>,
        val details: List<LogExplanation.Detail>,
        val groups: List<Row>,
        val counts: Map<LogTone, Int>,
        val genericCount: Int,
        val prefix: String,
        val preparationMs: Long,
        val sessions: List<Session>,
    ) {
        val latestSession:Session? get()=sessions.lastOrNull()
        fun scopeAvailable(scope:Scope):Boolean=when(scope){
            Scope.ALL->true
            Scope.VERSION->latestSession?.version!=null
            Scope.SESSION->latestSession!=null
        }
        private fun ranges(scope:Scope):List<IntRange> = when(scope){
            Scope.ALL->listOf(raw.indices)
            Scope.VERSION->latestSession?.version?.let{v->sessions.filter{it.version==v}.map{it.start..it.end}}.orEmpty()
            Scope.SESSION->latestSession?.let{listOf(it.start..it.end)}.orEmpty()
        }
        private val scopeStats=Scope.entries.associateWith{scope->
            if(scope==Scope.ALL) Stats(raw.size,counts)
            else {
                val c=mutableMapOf<LogTone,Int>();var total=0
                for(range in ranges(scope))for(i in range){val tone=raw[i].tone;c[tone]=(c[tone]?:0)+1;total++}
                Stats(total,c)
            }
        }
        fun stats(scope:Scope):Stats=scopeStats.getValue(scope)
        fun recordText(index: Int): String = raw[index].let {
            (sequenceOf(it.original) + it.stack.asSequence()).joinToString("\n")
        }
        fun text(row: Row): String = (row.start..row.end).joinToString("\n", transform = ::recordText)
        fun select(tone: LogTone?, readable: Boolean, query: String, newestFirst: Boolean, scope:Scope=Scope.ALL): List<Row> {
            val q = query.trim()
            val source = if (readable) groups.asSequence() else raw.indices.asSequence().map { Row(it, it) }
            val admitted=if(scope==Scope.ALL)null else BooleanArray(raw.size).also{mask->for(range in ranges(scope))for(i in range)mask[i]=true}
            // A mixed-severity summary is split by the raw tone filter. No warning
            // or error is hidden, and displayed counts still refer to raw records.
            val scoped=if(tone==null&&admitted==null)source else source.flatMap{row->
                sequence {
                    var first=-1
                    for(i in row.start..row.end){
                        if((admitted==null||admitted[i])&&(tone==null||raw[i].tone==tone)){if(first<0)first=i}
                        else if(first>=0){yield(Row(first,i-1));first=-1}
                    }
                    if(first>=0)yield(Row(first,row.end))
                }
            }
            val selected = scoped.filter { row ->
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
                if (last != null && (canGroup(entries[last.end], entries[i]) || last.count==1 && shortPair(entries[last.end],entries[i]))) {
                    groups[groups.lastIndex] = Row(last.start, i)
                } else groups.add(Row(i, i))
            }
            val prefix = rawText.lineSequence().takeWhile { LogDisplay.parseHeader(it) == null }.joinToString("\n")
            val starts=entries.indices.filter{entries[it].tag=="App"&&entries[it].event=="Session started"}
            val sessions=starts.mapIndexed{index,start->Session(start,(starts.getOrNull(index+1)?:entries.size)-1,
                versionMarker.find(entries[start].metadata)?.groupValues?.get(1))}
            Snapshot(entries, details, groups, entries.groupingBy { it.tone }.eachCount(), generic, prefix,
                (System.nanoTime() - started).coerceAtLeast(0) / 1_000_000,sessions)
        }
    }

    private val versionMarker=Regex("""^MediaLoader ([0-9]+(?:\.[0-9]+){1,4} \([0-9]+\))(?: · |$)""")
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
    fun historicalStart(e:DisplayLogEntry):Boolean=e.tag=="TaskCenter"&&e.event=="START"
    private fun elapsedBetween(a:DisplayLogEntry,b:DisplayLogEntry):Long?=runCatching{
        java.time.Duration.between(java.time.LocalDateTime.parse(a.timestamp.replace(' ','T')),
            java.time.LocalDateTime.parse(b.timestamp.replace(' ','T'))).toMillis()
    }.getOrNull()?.takeIf{it in 0..5000}
    fun recoveryDurations(a:DisplayLogEntry,b:DisplayLogEntry):Pair<Long,Long>? {
        if(a.tag!="Perf"||b.tag!="Perf"||a.event!="UI_STALL"||b.event!="UI_RECOVERED"||
            a.thread!="ML-ui-watchdog"||a.thread!=b.thread||a.tone!=LogTone.WARN||b.tone!=LogTone.INFO||
            b.stack.any{it.isNotBlank()})return null
        val dt=elapsedBetween(a,b)?:return null
        val x=fields(a);val y=fields(b)
        if(x["screen"].isNullOrBlank()||x["screen"]!=y["screen"])return null
        val start=x.number("blockedMs")?:return null;val end=y.number("blockedMs")?:return null
        if(end<start||kotlin.math.abs((end-start)-dt)>500L)return null
        return start to end
    }
    fun shortTaskPair(a:DisplayLogEntry,b:DisplayLogEntry):Boolean {
        if(!historicalStart(a)||b.tag!="TaskCenter"||a.tone in setOf(LogTone.WARN,LogTone.ERROR)||
            a.stack.any{it.isNotBlank()}||b.stack.any{it.isNotBlank()}||elapsedBetween(a,b)==null)return false
        val x=fields(a);val y=fields(b)
        val terminal=when(b.event){"COMPLETE"->"COMPLETE";"PARTIAL"->"PARTIAL";"FAIL"->"FAILED";"CANCEL"->"CANCELLED";else->return false}
        if(x["status"]!="RUNNING"||y["status"]!=terminal||x["op"]?.takeIf(identifier::matches)==null||
            x["op"]!=y["op"]||x["kind"].isNullOrBlank()||x["kind"]!=y["kind"]||x["trigger"]!=y["trigger"])return false
        val p=progress(a)?:return false;val q=progress(b)?:return false
        return p.total==q.total&&p.done<=q.done&&(y.number("elapsedMs")?:Long.MAX_VALUE)<=5000
    }
    private fun shortPair(a:DisplayLogEntry,b:DisplayLogEntry)=shortTaskPair(a,b)||recoveryDurations(a,b)!=null
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
