package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test

/** Synthetic signature representatives only; no original user logs are committed. */
class JournalCorpusTest {
    @Test fun allObservedSignatureFamiliesHaveSpecificExplanations() {
        val resource = requireNotNull(javaClass.getResourceAsStream("/qa236-journal-signatures.tsv"))
        val rows = resource.bufferedReader(Charsets.UTF_8).use { it.readLines() }
        var total = 0
        val tags = mutableSetOf<String>()
        val missing = mutableListOf<String>()
        val generic = setOf(
            "Зафиксирована ошибка. Подробности доступны в техническом журнале.",
            "Зафиксировано предупреждение. Подробности доступны в техническом журнале.",
            "Операция завершилась успешно.",
            "Событие приложения. Подробности доступны в техническом журнале.",
            "Техническое диагностическое событие."
        )
        for (row in rows) {
            val parts = row.split('\t', limit = 3)
            val tag = parts[0]
            total += parts[1].toInt()
            tags += tag
            val level = if (tag == "Crash") "ERROR" else "INFO"
            val raw = "2026-11-27 00:00:00 [$level] [$tag] [thread=example] " + parts[2]
            val entry = LogDisplay.entries(raw).single()
            val readable = LogDisplay.readable(entry)
            if (readable in generic || "пока нет точного пояснения" in readable ||
                LogDisplay.groupName(entry) == "Другие события") missing.add(tag + ": " + parts[2])
            assertEquals(raw, entry.original)
        }
        assertEquals(254, rows.size)
        assertEquals(57, tags.size)
        assertEquals(50744, total)
        assertTrue("Unexplained signature representatives: " + missing.joinToString("\n"), missing.isEmpty())
    }
}
