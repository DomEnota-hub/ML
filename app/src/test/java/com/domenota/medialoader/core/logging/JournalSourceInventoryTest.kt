package com.domenota.medialoader.core.logging

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Check the entire checked-out production Kotlin tree, not the default-branch search index.
 * Interpolated expressions and field-dependent variants are intentionally not claimed as replayed.
 */
class JournalSourceInventoryTest {
    @Test fun literalEmittersHaveSpecificExplanations() {
        val root = listOf(File("src/main/java"), File("app/src/main/java"))
            .firstOrNull { it.isDirectory } ?: error("Production source root missing")
        val calls = Regex("""\bAppLog\.(i|w|e|d|ok|verbose)\s*\(\s*"([^"\n]+)"\s*,\s*"([^"$\n]*)""")
        val sampled = Regex("""\bAppLog\.verboseSampled\s*\(\s*"([^"\n]+)"\s*,\s*"[^"\n]*"\s*,\s*"([^"$\n]*)""")
        val generic = setOf("Зафиксирована ошибка. Подробности доступны в техническом журнале.",
            "Зафиксировано предупреждение. Подробности доступны в техническом журнале.",
            "Операция завершилась успешно.", "Событие приложения. Подробности доступны в техническом журнале.",
            "Техническое диагностическое событие.")
        val sourceFiles = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val observed = linkedSetOf<String>()
        val missing = linkedSetOf<String>()
        var dynamic = 0
        for (file in sourceFiles) {
            val source = file.readText()
            val direct = calls.findAll(source).map { it.destructured.toList() }
            val sampledCalls = sampled.findAll(source).map { listOf("verbose", it.groupValues[1], it.groupValues[2]) }
            for ((method, tag, prefix) in direct + sampledCalls) {
                val message = prefix.replace("\\n", " ").trim().removeSuffix("·").trimEnd()
                if (message.isBlank()) { dynamic++; continue }
                val level = when (method) {
                    "w" -> "WARN"; "e" -> "ERROR"; "ok" -> "OK"; "i" -> "INFO"; else -> "DEBUG"
                }
                val signature = "$tag | $message"
                if (!observed.add(signature)) continue
                val entry = LogDisplay.entries("2026-11-27 00:00:00 [$level] [$tag] $message").single()
                val readable = LogDisplay.readable(entry)
                if (readable in generic || LogDisplay.groupName(entry) == "Другие события") {
                    missing.add(signature + " (" + file.name + ")")
                }
            }
        }
        assertTrue("Expected a production tree, got only " + sourceFiles.size + " Kotlin files", sourceFiles.size > 500)
        assertTrue("Expected actual log emitters", observed.size > 100)
        println("JOURNAL_SOURCE_INVENTORY files=" + sourceFiles.size + " prefixes=" + observed.size + " missing=" + missing.size + " dynamicEmptyPrefix=" + dynamic)
        assertTrue("Unexplained direct literal prefixes:\n" + missing.joinToString("\n"), missing.isEmpty())
    }
}
