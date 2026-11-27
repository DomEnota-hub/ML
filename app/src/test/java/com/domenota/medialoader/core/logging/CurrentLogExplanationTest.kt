package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

class CurrentLogExplanationTest {
    private fun entry(tag: String, message: String) =
        LogDisplay.entries("2026-11-27 00:00:00 [WARN] [$tag] [thread=example] $message").single()

    @Test fun directSourceEventsHaveSafeExplanations() {
        val auth = entry("VKAuth", "Web session saved · token=secret")
        assertTrue(LogDisplay.readable(auth).contains("сохранены"))
        assertFalse(LogDisplay.readable(auth).contains("secret"))
        assertEquals("Сеанс VK", LogDisplay.groupName(auth))
        assertTrue(LogDisplay.readable(entry("RUTUBE", "Download failed: private")).contains("не выполнено"))
        val history = LogDisplay.readable(entry("History", "History-only cleanup completed"))
        assertTrue(history.contains("истории"))
        assertFalse(history.contains("файл удалён"))
        val preserved = LogDisplay.readable(entry("Trash", "Physical delete failed; entry preserved · id=secret"))
        assertTrue(preserved.contains("запись сохранена"))
        assertFalse(preserved.contains("secret"))
        assertTrue(LogDisplay.readable(entry("Library", "Phone scan failed")).contains("ошибкой"))
        assertTrue(LogDisplay.readable(entry("DuplicateScanMetrics", "gallery coverage=20 cached=10 computed=8 failed=2")).contains("В кеше: 10"))
    }
}
