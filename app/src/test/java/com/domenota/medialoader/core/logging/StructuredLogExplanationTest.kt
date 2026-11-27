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

    @Test fun additionalCurrentSourceStatesStayDistinct() {
        val waiting = LogDisplay.readable(entry("OCR", "OCR_RUN phase=WAITING_CHARGE indexed=5 total=20"))
        assertTrue(waiting.contains("Ожидание зарядки"))
        val exit = LogDisplay.readable(entry("ProcessExit", "Previous process exit · type=JAVA_CRASH", "WARN"))
        assertTrue(exit.contains("Аварийное завершение"))
        val folder = LogDisplay.readable(entry("FolderVisibility", "STOP id=private confirmed=2 total=3", "WARN"))
        assertTrue(folder.contains("2 из 3")); assertFalse(folder.contains("private"))
        val counters = LogDisplay.readable(entry("Perf", "LIBRARY_WORK · COMPOSITION=3 MEDIASTORE_SCAN=8 MEDIASTORE_QUERY=9 THUMBNAIL_DECODE=2 LIBRARY_INDEX_BUILD=1"))
        assertTrue(counters.contains("Сканирований: 8")); assertTrue(counters.contains("Запросов: 9"))
        val projection = LogDisplay.readable(entry("Perf", "LIBRARY_PROJECTIONS · MUSIC_BROWSE[initial=1,input=2,access=0,done=3,aborted=1,reuse=5,totalMs=25,maxMs=10]"))
        assertTrue(projection.contains("завершено 3")); assertTrue(projection.contains("повторно использовано 5"))
    }
    @Test fun waveformDurationReadFailureAndMissingAreDistinct() {
        val failure = entry("AudioWaveform", "WAVEFORM_DURATION_READ_FAILED", "DEBUG")
        val missing = entry("AudioWaveform", "WAVEFORM_DURATION_MISSING versionProven=true", "DEBUG")
        assertTrue(LogDisplay.readable(failure).contains("Ошибка чтения длительности"))
        assertTrue(LogDisplay.readable(missing).contains("трек пропущен"))
        assertTrue(LogDisplay.readable(missing).contains("Версия источника подтверждена"))
        assertTrue(LogDisplay.readable(entry("AudioWaveform", "WAVEFORM_DURATION_MISSING versionProven=false")).contains("Версия источника не подтверждена"))
        assertFalse(LogDisplay.readable(entry("AudioWaveform", "WAVEFORM_DURATION_MISSING")).contains("Версия источника"))
        assertEquals("Звуковые волны", LogDisplay.groupName(failure))
        assertEquals(LogTone.DEBUG, failure.tone)
    }
    @Test fun unknownTagsAndDownloadNamesNeverBecomeFalseSuccess() {
        val unknown = entry("FuturePrivateTag", "MP3 stream verified · name=private")
        assertFalse(LogDisplay.readable(unknown).contains("проверку"))
        val started = entry("Download", "Started · name=Completed.mp3")
        assertTrue(LogDisplay.readable(started).contains("ещё не подтверждено"))
        val attempt = entry("HitMoz", "MP3 HTTP attempt 2/3", "DEBUG")
        assertTrue(LogDisplay.readable(attempt).contains("2 из 3"))
    }
}
