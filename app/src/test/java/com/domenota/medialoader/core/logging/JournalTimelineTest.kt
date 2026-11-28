package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class JournalTimelineTest {
    private fun line(time: String, tag: String, event: String, level: String = "DEBUG") =
        "2026-11-27 " + time + " [" + level + "] [" + tag + "] [thread=worker] " + event

    @Test fun groupsOnlyConsecutiveMonotonicSameOperationAndKeepsEveryOriginal() {
        val lines = listOf(
            line("09:00:01", "DownloadProgress", "id=a · bytes=2 · total=10"),
            line("09:00:02", "DownloadProgress", "id=a · bytes=4 · total=10"),
            line("09:00:03", "DownloadProgress", "id=b · bytes=4 · total=10"),
            line("09:00:04", "DownloadProgress", "id=a · bytes=6 · total=10"),
            line("09:00:05", "DownloadProgress", "id=a · bytes=1 · total=10"),
            line("09:00:06", "DownloadProgress", "id=a · bytes=2 · total=10", "WARN"),
        )
        val s = JournalTimeline.prepare(lines.joinToString("\n"))
        assertEquals(listOf(2,1,1,1,1), s.groups.map { it.count })
        assertEquals(lines, s.raw.indices.map(s::recordText))
        assertEquals(lines.take(2).joinToString("\n"), s.text(s.groups.first()))
        assertEquals(6, s.select(null,false,"",false).size)
        assertEquals(5, s.select(LogTone.DEBUG,false,"",false).size)
        assertEquals(1, s.select(LogTone.WARN,true,"",false).size)
        assertEquals(5, s.select(null,true,"",false).size)
        assertEquals(s.groups.reversed(), s.select(null,true,"",true))
        assertEquals(0, s.select(null,true,"bytes=4",false).first().start)
        assertEquals(2, s.select(null,true,"bytes=4",false).size)
    }

    @Test fun ocrStatusFailureAndUnknownProgressAreNeverCollapsed() {
        val a = "OCR_RUN session=ocr-1 phase=RUNNING indexed=1 total=10 failed=0 unverified=0"
        val lines = listOf(
            line("09:00:01","OCR",a),
            line("09:00:02","OCR",a.replace("indexed=1","indexed=2")),
            line("09:00:03","OCR",a.replace("failed=0","failed=1")),
            line("09:00:04","OCR",a.replace("RUNNING","CANCELLED")),
            line("09:00:05","OCR",a.replace("total=10","total=0")),
            line("09:00:06","OCR",a.replace("total=10","total=10 total=20")),
        )
        val s = JournalTimeline.prepare(lines.joinToString("\n"))
        assertEquals(listOf(2,1,1,1,1),s.groups.map{it.count})
        assertNull(JournalTimeline.progress(s.raw[4])); assertNull(JournalTimeline.progress(s.raw[5]))
        assertEquals(0.2f, JournalTimeline.progress(s.raw[1])!!.fraction, 0.001f)
    }

    @Test fun exportUsesSameSpecificInterpretationWithoutRemovingRawAndStack() {
        val header = line("09:00:01", "Library", "Phone scan finished · count=1194 · mode=MUSIC")
        val stack = "  test stack\n\n  continuation"
        val raw = "leading orphan\n" + header + "\n" + stack + "\n" + line("09:00:02","Future","private/path")
        val s = JournalTimeline.prepare(raw)
        assertEquals("leading orphan", s.prefix)
        assertTrue(s.recordText(0).contains(stack))
        assertEquals(1, s.genericCount)
        val annotated = LogExplanation.annotate(raw)
        assertTrue(annotated.contains(header + "\n" + stack))
        assertTrue(annotated.contains("Найдено файлов: 1194"))
        assertTrue(annotated.contains("Future] [thread=worker] private/path"))
        assertFalse(s.details[1].meaning.contains("private/path"))
    }

    @Test fun allFourDynamicEmitterSitesHaveSafeBoundedMeaning() {
        val rows = listOf(
            line("09:00:01","History","Open aborted · id=private · status=MISSING","WARN"),
            line("09:00:02","History","Share failed · id=private · IOException: secret","WARN"),
            line("09:00:03","Browser","instagram · private text","INFO"),
            line("09:00:04","Browser","vk · injection=true","INFO"),
        )
        val s = JournalTimeline.prepare(rows.joinToString("\n"))
        assertEquals(0,s.genericCount)
        assertTrue(s.details[0].meaning.contains("Файл отсутствует"))
        assertTrue(s.details[1].meaning.contains("отправить"))
        assertTrue(s.details[2].meaning.contains("Instagram"))
        assertTrue(s.details[3].meaning.contains("подтвердил"))
        assertFalse(s.details.any { "private" in it.meaning || "secret" in it.meaning })
    }

    @Test fun largeArchiveRepresentativesMeasureCoverageTimeAndHeapWithoutPersistingLogs() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/qa236-journal-signatures.tsv"))
        val rows = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        val raw = buildString {
            for (row in rows) {
                val p = row.split('\t',limit=3)
                repeat(p[1].toInt()) {
                    if (isNotEmpty()) append('\n')
                    append(line("09:00:01",p[0],p[2],if(p[0]=="Crash")"ERROR" else "INFO"))
                }
            }
        }
        val runtime = Runtime.getRuntime()
        val heapBefore = runtime.totalMemory() - runtime.freeMemory()
        val started = System.nanoTime()
        val s = JournalTimeline.prepare(raw)
        val ms = (System.nanoTime() - started) / 1_000_000
        val heapAfter = runtime.totalMemory() - runtime.freeMemory()
        assertEquals(50744,s.raw.size)
        assertEquals(50744,s.groups.sumOf{it.count})
        assertEquals(0,s.genericCount)
        assertEquals(50744,s.select(null,false,"",false).size)
        println("JOURNAL_LARGE_CORPUS records=" + s.raw.size + " generic=" + s.genericCount +
            " prepareMs=" + ms + " heapDeltaBytes=" + (heapAfter - heapBefore) + " groups=" + s.groups.size)
    }

    @Test fun chronologicalSortUsesRecordedTimeAndExactRepeatedObservationsNeverCrossIdentity() {
        val rows = listOf(
            line("09:00:03","Thumbnail","Fetch · host=a.invalid · status=403"),
            line("09:00:04","Thumbnail","Fetch · host=a.invalid · status=403"),
            line("09:00:05","Thumbnail","Fetch · host=b.invalid · status=403"),
            line("09:00:01","Perf","READ_MODEL_GUARD kind=ALIASES reason=DISABLED retained=false"),
            line("09:00:02","Perf","READ_MODEL_GUARD kind=ALIASES reason=DISABLED retained=false"),
            line("09:00:06","Perf","READ_MODEL_GUARD kind=ALIASES reason=ACCESS retained=false"),
            line("09:00:07","Thumbnail","Fetch · host=a.invalid · status=403","WARN"),
            line("09:00:08","Thumbnail","Fetch · host=a.invalid · status=403","WARN"),
        )
        val s = JournalTimeline.prepare(rows.joinToString("\n"))
        assertEquals(listOf(2,1,2,1,1,1),s.groups.map { it.count })
        assertEquals(listOf(3,4,0,1,2,5,6,7),s.select(null,false,"",false).map{it.start})
        assertEquals(listOf(7,6,5,2,0,3),s.select(null,true,"",true).map{it.start})
        assertEquals(rows[4],s.recordText(4))
    }
    @Test fun shortTaskSummaryPreservesWarningAndRawToneFilters(){
        val start=line("09:00:01.001","TaskCenter","START · op=abc · kind=WAVEFORM · status=RUNNING · trigger=BACKGROUND · done=0 · total=2 · elapsedMs=1","INFO")
        val end=line("09:00:01.301","TaskCenter","PARTIAL · op=abc · kind=WAVEFORM · status=PARTIAL · trigger=BACKGROUND · done=2 · total=2 · elapsedMs=300 · skipped=2","WARN")
        val s=JournalTimeline.prepare("$start\n$end")
        assertEquals(listOf(JournalTimeline.Row(0,1)),s.groups)
        assertEquals(listOf(JournalTimeline.Row(0,0)),s.select(LogTone.INFO,true,"",true))
        assertEquals(listOf(JournalTimeline.Row(1,1)),s.select(LogTone.WARN,true,"",true))
        assertEquals(listOf(start,end),s.raw.indices.map(s::recordText))
        assertTrue(JournalTimeline.historicalStart(s.raw.first()))
        assertEquals(2,JournalTimeline.prepare("$start\n"+end.replace("op=abc","op=other")).groups.size)
        assertEquals(2,JournalTimeline.prepare("$start\n"+end.replace("elapsedMs=300","elapsedMs=9000")).groups.size)
    }
    @Test fun scopesExcludeUnidentifiedPrefixAndDistinguishSessionFromVersion(){
        val events=listOf(line("09:00:00","Library","unknown"),line("09:00:01","App","Session started · MediaLoader 1.7.236 (296) · Android 16","INFO"),
            line("09:00:02","App","Session started · MediaLoader 1.7.237 (297) · Android 16","INFO"),
            line("09:00:03","Library","one"),line("09:00:04","App","Session started · MediaLoader 1.7.237 (297) · Android 16","INFO"),
            line("09:00:05","Library","two"))
        val s=JournalTimeline.prepare(events.joinToString("\n"))
        assertEquals("1.7.237 (297)",s.latestSession?.version)
        assertEquals(4,s.stats(JournalTimeline.Scope.VERSION).total)
        assertEquals(2,s.stats(JournalTimeline.Scope.SESSION).total)
        assertEquals(listOf(2,3,4,5),s.select(null,false,"",false,JournalTimeline.Scope.VERSION).map{it.start})
        assertEquals(listOf(5,4),s.select(null,false,"",true,JournalTimeline.Scope.SESSION).map{it.start})
    }
    @Test fun watchdogRecoveryRequiresSameThreadScreenAndConsistentDuration(){
        val a=line("09:00:00.000","Perf","UI_STALL · blockedMs=752 · screen=home:GALLERY:PHONE","WARN").replace("thread=worker","thread=ML-ui-watchdog")
        val b=line("09:00:00.250","Perf","UI_RECOVERED · blockedMs=1002 · screen=home:GALLERY:PHONE","INFO").replace("thread=worker","thread=ML-ui-watchdog")
        val s=JournalTimeline.prepare(a+"\n    at synthetic\n"+b)
        assertEquals(listOf(JournalTimeline.Row(0,1)),s.groups)
        assertEquals(752L to 1002L,JournalTimeline.recoveryDurations(s.raw[0],s.raw[1]))
        assertTrue(s.recordText(0).contains("at synthetic"))
        assertEquals(listOf(JournalTimeline.Row(0,0)),s.select(LogTone.WARN,true,"",false))
        assertEquals(2,JournalTimeline.prepare(a+"\n"+b.replace("home:GALLERY","home:MUSIC")).groups.size)
    }
}
