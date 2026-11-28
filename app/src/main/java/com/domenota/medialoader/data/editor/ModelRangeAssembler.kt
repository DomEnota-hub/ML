package com.domenota.medialoader.data.editor

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

internal data class AssemblySource(val id: String, val bytes: Long, val sha256: String)
internal data class AssemblyRange(val outputOffset: Long, val source: String, val sourceOffset: Long,
    val length: Long, val sha256: String)
internal data class ModelAssemblyPlan(val outputBytes: Long, val outputSha256: String,
    val skeletonBytes: Long, val skeletonSha256: String, val sources: List<AssemblySource>,
    val ranges: List<AssemblyRange>)

/** Streaming, non-executable recipe: only verified source byte ranges and literal graph bytes. */
internal object ModelRangeAssembler {
    private val shaPattern = Regex("[0-9a-f]{64}")
    private fun bad(): Nothing = throw IOException("Некорректная схема сборки модели.")
    internal fun digest() = MessageDigest.getInstance("SHA-256")
    internal fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    fun validate(plan: ModelAssemblyPlan) {
        if (plan.outputBytes !in 1L..110_000_000L || plan.skeletonBytes !in 1L..plan.outputBytes ||
            !shaPattern.matches(plan.outputSha256) || !shaPattern.matches(plan.skeletonSha256) ||
            plan.sources.size !in 1..8 || plan.ranges.size !in 1..4096) bad()
        val sources = plan.sources.associateBy { it.id }
        if (sources.size != plan.sources.size || plan.sources.any {
                !it.id.matches(Regex("[a-z0-9_-]{1,32}")) || it.bytes !in 1L..1_000_000_000L ||
                    !shaPattern.matches(it.sha256)
            }) bad()
        var end = 0L
        var payload = 0L
        for (r in plan.ranges) {
            val source = sources[r.source] ?: bad()
            if (r.length <= 0L || r.length > plan.outputBytes ||
                r.length > source.bytes || r.outputOffset < end ||
                r.outputOffset > plan.outputBytes - r.length ||
                r.sourceOffset < 0L || r.sourceOffset > source.bytes - r.length ||
                !shaPattern.matches(r.sha256)) bad()
            end = r.outputOffset + r.length
            payload += r.length
        }
        if (plan.outputBytes - payload != plan.skeletonBytes ||
            plan.ranges.map { it.source }.toSet() != sources.keys) bad()
    }

    /**
     * Source files are private staging files already checked against their complete SHA-256
     * by the downloader. All consumed slices, skeleton bytes, and final output are checked
     * again here. No pickle parser, executable data, or large in-memory model.
     * The output must be a new staging file, never the installed model.
     */
    fun assemble(
        plan: ModelAssemblyPlan, skeleton: InputStream, sourceFiles: Map<String, File>,
        output: File, checkCancelled: () -> Unit = {}, onProgress: (Long) -> Unit = {},
    ) {
        validate(plan)
        if (sourceFiles.keys != plan.sources.map { it.id }.toSet() ||
            plan.sources.any { sourceFiles[it.id]?.let { f -> !f.isFile || f.length() != it.bytes } != false })
            throw IOException("Исходные файлы модели отсутствуют или имеют неверный размер.")
        checkCancelled()
        if (!output.createNewFile()) throw IOException("Временный файл модели уже существует.")
        val handles = mutableMapOf<String, RandomAccessFile>()
        var success = false
        try {
            sourceFiles.forEach { (id, file) -> handles[id] = RandomAccessFile(file, "r") }
            val outputDigest = digest()
            val skeletonDigest = digest()
            val buffer = ByteArray(64 * 1024)
            var written = 0L
            var literalBytes = 0L
            FileOutputStream(output).use { sink ->
                fun copy(length: Long, input: (ByteArray, Int) -> Int, partDigest: MessageDigest) {
                    var remaining = length
                    while (remaining > 0L) {
                        checkCancelled()
                        val count = input(buffer, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count <= 0 || count > remaining)
                            throw IOException("Неожиданный конец компонентов модели.")
                        sink.write(buffer, 0, count)
                        partDigest.update(buffer, 0, count)
                        outputDigest.update(buffer, 0, count)
                        remaining -= count
                        written += count
                        onProgress(written)
                    }
                }
                fun literal(length: Long) {
                    copy(length, { data, n -> skeleton.read(data, 0, n) }, skeletonDigest)
                    literalBytes += length
                }
                for (r in plan.ranges) {
                    literal(r.outputOffset - written)
                    val source = handles.getValue(r.source)
                    source.seek(r.sourceOffset)
                    val sliceDigest = digest()
                    copy(r.length, { data, n -> source.read(data, 0, n) }, sliceDigest)
                    if (hex(sliceDigest.digest()) != r.sha256)
                        throw IOException("Контрольная сумма компонента модели не совпала.")
                }
                literal(plan.outputBytes - written)
                checkCancelled()
                if (literalBytes != plan.skeletonBytes || skeleton.read() != -1 ||
                    hex(skeletonDigest.digest()) != plan.skeletonSha256 ||
                    written != plan.outputBytes || hex(outputDigest.digest()) != plan.outputSha256)
                    throw IOException("Собранная модель не прошла SHA-256.")
                sink.fd.sync()
            }
            checkCancelled()
            success = true
        } finally {
            handles.values.forEach { runCatching { it.close() } }
            if (!success) output.delete()
        }
    }
}
