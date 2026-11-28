package com.domenota.medialoader.ui.navigation

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class UserFacingToneInventoryTest {
    @Test
    fun scanProductionTextForFormalAddressCandidates() {
        val root = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val candidates = Regex("(?<![А-Яа-яЁё])(?:[Вв]ы|[Вв]ам|[Вв]ас|[Вв]аш[А-Яа-яЁё]*|[А-Яа-яЁё]+(?:ите|йте|тесь|итесь))(?![А-Яа-яЁё])")
        val files = root.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java", "xml", "json", "html", "txt", "md") }.toList()
        assertTrue("Production source inventory must not be empty", files.size > 500)
        var count = 0
        files.forEach { file ->
            file.useLines { lines ->
                lines.forEachIndexed { index, line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("<!--")) return@forEachIndexed
                    if (!trimmed.contains('"') && file.extension in setOf("kt", "java")) return@forEachIndexed
                    val words = candidates.findAll(trimmed).map { it.value }.distinct().toList()
                    if (words.isNotEmpty()) {
                        count++
                        println("TONE_CANDIDATE " + file.relativeTo(root).invariantSeparatorsPath + ":" + (index + 1) + " " + words.joinToString(","))
                    }
                }
            }
        }
        println("TONE_AUDIT files=" + files.size + " candidateLines=" + count)
        val home = File(root, "java/com/domenota/medialoader/ui/components/MusicHomeSection.kt").readText()
        assertTrue(!home.contains("которые не слушали"))
    }
}
