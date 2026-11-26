package com.domenota.medialoader.data.editor

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ModelRangeAssemblerTest {
    private fun hash(bytes: ByteArray) = ModelRangeAssembler.hex(ModelRangeAssembler.digest().digest(bytes))
    private fun fixture(run: (ModelAssemblyPlan, ByteArray, Map<String, File>, File, ByteArray) -> Unit) {
        val root = Files.createTempDirectory("ml-assembly-test").toFile()
        try {
            val first = "xABCy".toByteArray()
            val second = "zDEFw".toByteArray()
            val literal = "head-middle-tail".toByteArray()
            val expected = "headABC-middle-DEFtail".toByteArray()
            val files = mapOf("dex" to File(root,"dex").apply { writeBytes(first) },
                "vgg" to File(root,"vgg").apply { writeBytes(second) })
            val plan = ModelAssemblyPlan(expected.size.toLong(), hash(expected), literal.size.toLong(), hash(literal),
                listOf(AssemblySource("dex",first.size.toLong(),hash(first)), AssemblySource("vgg",second.size.toLong(),hash(second))),
                listOf(AssemblyRange(4,"dex",1,3,hash("ABC".toByteArray())),
                    AssemblyRange(15,"vgg",1,3,hash("DEF".toByteArray()))))
            run(plan, literal, files, File(root,"output.part"), expected)
        } finally { root.deleteRecursively() }
    }

    @Test fun reconstructsExactOutputFromTwoPinnedSources() = fixture { plan, literal, files, output, expected ->
        val progress = mutableListOf<Long>()
        ModelRangeAssembler.assemble(plan, ByteArrayInputStream(literal), files, output, onProgress = { progress += it })
        assertArrayEquals(expected, output.readBytes())
        assertEquals(plan.outputBytes, progress.last())
        assertTrue(progress.zipWithNext().all { (a,b) -> b > a })
    }

    @Test fun rejectsCorruptedSliceAndDeletesPartialOutput() = fixture { plan, literal, files, output, _ ->
        files.getValue("dex").writeBytes("xBADy".toByteArray())
        val failure = runCatching { ModelRangeAssembler.assemble(plan, ByteArrayInputStream(literal), files, output) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(output.exists())
    }

    @Test fun rejectsOverlapBoundsAndUnknownSourceBeforeWriting() = fixture { plan, literal, files, output, _ ->
        val changes = listOf(plan.ranges[1].copy(outputOffset=5), plan.ranges[1].copy(sourceOffset=Long.MAX_VALUE),
            plan.ranges[1].copy(source="other"), plan.ranges[1].copy(length=Long.MAX_VALUE))
        changes.forEach { range ->
            assertTrue(runCatching { ModelRangeAssembler.assemble(plan.copy(ranges=listOf(plan.ranges[0],range)),
                ByteArrayInputStream(literal), files, output) }.exceptionOrNull() is IOException)
            assertFalse(output.exists())
        }
    }

    @Test fun rejectsTruncatedExtraOrWrongSkeletonAndWrongFinalHash() = fixture { plan, literal, files, output, _ ->
        val bad = listOf(literal.copyOf(literal.size-1), literal + byteArrayOf(0), literal.copyOf().also { it[0]=0 })
        bad.forEach {
            assertTrue(runCatching { ModelRangeAssembler.assemble(plan, ByteArrayInputStream(it), files, output) }.exceptionOrNull() is IOException)
            assertFalse(output.exists())
        }
        assertTrue(runCatching { ModelRangeAssembler.assemble(plan.copy(outputSha256="0".repeat(64)),
            ByteArrayInputStream(literal), files, output) }.exceptionOrNull() is IOException)
        assertFalse(output.exists())
    }

    @Test fun cancellationRemovesOnlyStagingAndExistingFileIsNeverReplaced() = fixture { plan, literal, files, output, _ ->
        var checks = 0
        assertTrue(runCatching { ModelRangeAssembler.assemble(plan, ByteArrayInputStream(literal), files, output,
            checkCancelled = { if (++checks == 3) throw CancellationException("cancel") }) }.exceptionOrNull() is CancellationException)
        assertFalse(output.exists())
        assertTrue(files.values.all { it.isFile })
        output.writeText("keep")
        assertTrue(runCatching { ModelRangeAssembler.assemble(plan, ByteArrayInputStream(literal), files, output) }.exceptionOrNull() is IOException)
        assertEquals("keep", output.readText())
    }
}
