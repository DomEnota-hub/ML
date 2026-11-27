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
}
