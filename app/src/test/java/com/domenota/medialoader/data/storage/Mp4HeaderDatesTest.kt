package com.domenota.medialoader.data.storage

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class Mp4HeaderDatesTest {
    private val date = 1700000000123L
    private fun box(type: String, payload: ByteArray, extended: Boolean = false): ByteArray =
        ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use {
            it.writeInt(if (extended) 1 else payload.size + 8); it.writeBytes(type)
            if (extended) it.writeLong(payload.size.toLong() + 16)
            it.write(payload)
        } }.toByteArray()
    private fun header(type: String, version: Int): ByteArray {
        val size = when(type) { "mvhd" -> 100; "tkhd" -> 84; else -> 24 } + if (version == 1) 12 else 0
        return box(type, ByteArray(size).also { it[0] = version.toByte() })
    }
    private fun fixture(version: Int, extended: Boolean = false): ByteArray {
        val video = box("trak", header("tkhd", version) + box("mdia", header("mdhd", version)))
        val audio = box("trak", header("tkhd", version) + box("mdia", header("mdhd", version)))
        return box("ftyp", "isom0000".toByteArray()) +
            box("moov", header("mvhd", version) + video + audio, extended) +
            box("mdat", ByteArray(2048) { (it % 251).toByte() })
    }
    private fun files(bytes: ByteArray, run: (File, File) -> Unit) {
        val directory = Files.createTempDirectory("mp4-dates-qa").toFile()
        val source = File(directory, "synthetic.mp4").apply { writeBytes(bytes) }
        try { run(source, File(directory, "dated.mp4")); assertArrayEquals(bytes, source.readBytes()) }
        finally { directory.deleteRecursively() }
    }

    @Test fun versionsZeroAndOnePreserveEveryNonDateByte() {
        for (version in 0..1) for (extended in listOf(false, true)) files(fixture(version, extended)) { source, out ->
            Mp4HeaderDates.copy(source, out, date)
            Mp4HeaderDates.verify(source, out, date)
            assertEquals(source.length(), out.length())
            val before = source.readBytes(); val after = out.readBytes()
            val expected = ByteBuffer.allocate(if (version == 0) 4 else 8).apply {
                val seconds = date / 1000 + 2082844800L
                if (version == 0) putInt(seconds.toInt()) else putLong(seconds)
            }.array()
            var seen = 0
            for (i in 4 until before.size - 4) {
                val type = String(before, i, 4, Charsets.ISO_8859_1)
                if (type !in setOf("mvhd", "tkhd", "mdhd")) continue
                val start = i + 8
                for (offset in listOf(start, start + expected.size)) {
                    assertArrayEquals(expected, after.copyOfRange(offset, offset + expected.size))
                    expected.copyInto(before, offset)
                }
                seen++
            }
            assertEquals(5, seen)
            assertArrayEquals(before, after)
        }
    }

    @Test fun corruptionOutsideDateFieldsIsRejected() = files(fixture(0)) { source, out ->
        Mp4HeaderDates.copy(source, out, date)
        val bytes = out.readBytes().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        out.writeBytes(bytes)
        try { Mp4HeaderDates.verify(source, out, date); fail("Changed payload accepted") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("вне полей дат")) }
    }

    @Test fun malformedDuplicateAndUnsupportedHeadersFailBeforeWritingOutput() {
        val base = fixture(0)
        val unsupported = base.copyOf().apply {
            val at = String(this, Charsets.ISO_8859_1).indexOf("mvhd") + 4
            this[at] = 2
        }
        val duplicate = box("moov", header("mvhd", 0) + header("mvhd", 0))
        val huge = ByteBuffer.allocate(8).putInt(Int.MAX_VALUE).put("moov".toByteArray()).array()
        for (bad in listOf(base.copyOf(base.size - 1), unsupported, duplicate, huge)) files(bad) { source, out ->
            try { Mp4HeaderDates.copy(source, out, date); fail("Malformed header accepted") }
            catch (_: IllegalStateException) { assertFalse(out.exists()) }
        }
    }

    @Test fun sameFileAndInvalidDatesAreRejected() = files(fixture(0)) { source, out ->
        try { Mp4HeaderDates.copy(source, source, date); fail("Source overwrite accepted") }
        catch (_: IllegalStateException) { }
        try { Mp4HeaderDates.copy(source, out, -1); fail("Invalid date accepted") }
        catch (_: IllegalStateException) { assertFalse(out.exists()) }
    }
}
