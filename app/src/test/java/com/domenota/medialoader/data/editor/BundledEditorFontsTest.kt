package com.domenota.medialoader.data.editor

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Test

class BundledEditorFontsTest {
    @Test fun bundledTrueTypeFontsAreReadableAndIncludeCyrillic() {
        val folder = listOf(File("src/main/assets/text-fonts"), File("app/src/main/assets/text-fonts"))
            .first { it.isDirectory }
        val fonts = folder.listFiles()!!.filter { it.extension == "ttf" }
        assertEquals(23, fonts.size)
        fonts.forEach { file ->
            assertTrue("Missing Cyrillic in ${file.name}", containsCyrillicGlyphs(file))
        }
        assertEquals(4, folder.listFiles()!!.count { it.name.startsWith("LICENSE-") })
    }

    private fun containsCyrillicGlyphs(file: File): Boolean = RandomAccessFile(file, "r").use { data ->
        assertEquals(0x00010000, data.readInt())
        val tables = data.readUnsignedShort()
        data.skipBytes(6)
        var cmap = 0L
        repeat(tables) {
            val tag = data.readInt(); data.readInt()
            val offset = data.readInt().toLong() and 0xffffffffL
            data.readInt()
            if (tag == 0x636d6170) cmap = offset
        }
        assertTrue(cmap > 0)
        data.seek(cmap); data.readUnsignedShort()
        val subtables = data.readUnsignedShort()
        val offsets = List(subtables) {
            data.readUnsignedShort(); data.readUnsignedShort()
            cmap + (data.readInt().toLong() and 0xffffffffL)
        }
        for (offset in offsets) {
            data.seek(offset)
            if (data.readUnsignedShort() != 4) continue
            data.skipBytes(4)
            val count = data.readUnsignedShort() / 2
            data.skipBytes(6)
            val ends = IntArray(count) { data.readUnsignedShort() }
            data.skipBytes(2)
            val starts = IntArray(count) { data.readUnsignedShort() }
            val deltas = IntArray(count) { data.readShort().toInt() }
            val rangePosition = data.filePointer
            val ranges = IntArray(count) { data.readUnsignedShort() }
            val supported = "ПриветЁёжик".all { char ->
                val code = char.code
                val index = ends.indices.firstOrNull { code in starts[it]..ends[it] }
                if (index == null) false else if (ranges[index] == 0) {
                    ((code + deltas[index]) and 0xffff) != 0
                } else {
                    data.seek(rangePosition + index * 2 + ranges[index] + (code - starts[index]) * 2)
                    val glyph = data.readUnsignedShort()
                    glyph != 0 && ((glyph + deltas[index]) and 0xffff) != 0
                }
            }
            if (supported) return@use true
        }
        false
    }
}
