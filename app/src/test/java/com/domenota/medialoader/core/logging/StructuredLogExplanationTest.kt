package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class StructuredLogExplanationTest {
    private fun entry(tag: String, message: String, level: String = "INFO") =
        LogDisplay.entries("2026-11-27 09:11:18.000 [$level] [$tag] [thread=worker] $message").single()

    @Test fun threadIsSeparateAndOriginalIsPreserved() {
        val e = entry("HitMoz", "MP3 stream verified · bytes=100")
        assertEquals("worker", e.thread)
        assertEquals("MP3 stream verified", e.event)
        assertEquals("bytes=100", e.metadata)
        assertEquals(LogTone.OK, e.tone)
        assertTrue(e.original.contains("[thread=worker]"))
    }
    @Test fun numericFieldsFromInlineAndSeparatedRecords() {
        val scan = LogDisplay.readable(entry("Library", "Phone scan finished · mode=MUSIC · count=1194 · ms=242"))
        assertTrue(scan.contains("1194")); assertTrue(scan.contains("242")); assertTrue(scan.contains("Музыка"))
        val ocr = LogDisplay.readable(entry("OCR", "OCR_RUN session=private phase=RUNNING indexed=25 total=10883 failed=7 unverified=0 ms=83390", "DEBUG"))
        assertTrue(ocr.contains("25 из 10883")); assertTrue(ocr.contains("Не обработано: 7")); assertFalse(ocr.contains("private"))
        val download = LogDisplay.readable(entry("DownloadProgress", "id=private · bytes=655360 · total=7320129", "DEBUG"))
        assertTrue(download.contains("655360 из 7320129")); assertFalse(download.contains("private"))
    }
    @Test fun unknownOrInvalidFieldsDoNotInventValues() {
        val unknown = entry("OCR", "OCR_RUN session=hidden phase=UNKNOWN indexed=20 total=10 failed=-1")
        val s = LogDisplay.readable(unknown)
        assertTrue(s.contains("20")); assertFalse(s.contains("из 10")); assertFalse(s.contains("Не обработано"))
        assertFalse(s.contains("UNKNOWN")); assertFalse(s.contains("hidden"))
        val duplicate = entry("Library", "Phone scan finished · count=1 · count=2")
        assertFalse(LogDisplay.readable(duplicate).contains("Найдено файлов"))
        assertNull(StructuredLogExplanation.readable(entry("Library", "Future event · count=99")))
    }
    @Test fun privacyAndNonTerminalState() {
        val guard = LogDisplay.readable(entry("Perf", "READ_MODEL_GUARD kind=AUDIO_SESSION reason=DISABLED retained=false repeatedSuppressed=53 windowMs=1000"))
        assertTrue(guard.contains("Аудиосессия")); assertTrue(guard.contains("53")); assertFalse(guard.contains("ошибка", true))
        val exit = LogDisplay.readable(entry("ProcessExit", "Previous process exit · type=LOW_MEMORY · pssKiB=264159 · atLocal=private", "WARN"))
        assertTrue(exit.contains("Нехватка памяти")); assertTrue(exit.contains("264159")); assertFalse(exit.contains("private"))
        assertEquals("Другие события", LogDisplay.groupName(entry("PRIVATE_TAG", "x")))
        val pending = LogDisplay.readable(entry("Download", "Retry queued · name=private"))
        assertTrue(pending.contains("очередь")); assertFalse(pending.contains("private"))
    }
    @Test fun threadedThumbnailCompactionStillMatches() {
        val a = entry("Thumbnail", "Fetch · host=private · status=403", "DEBUG")
        assertEquals(2, LogDisplay.compact(listOf(a,a)).single().repeats)
    }
}
