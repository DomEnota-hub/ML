package com.domenota.medialoader.data.editor

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A remote revision is information, NOT authorization to install arbitrary new weights. */
internal enum class ModelUpdateState { CURRENT, REVIEW_REQUIRED, NOT_INSTALLED, UNVERIFIABLE, UNAVAILABLE }

internal data class ExpectedModelBlob(
    val filename: String,
    val sha256: String,
    val bytes: Long? = null,
)

internal data class RemoteModelBlob(
    val filename: String,
    val sha256: String,
    val bytes: Long,
)

internal data class ModelUpdateResult(
    val state: ModelUpdateState,
    val detail: String,
    val revision: String? = null,
    val publishedBytes: Long? = null,
)

/**
 * Pure, fail-closed comparison of pinned, code-reviewed model metadata with
 * the current upstream model-repository manifest. Never downloads model weights.
 */
internal object ModelUpdatePolicy {
    private val sha256Pattern = Regex("[0-9a-f]{64}")
    private val revisionPattern = Regex("[0-9a-f]{40}")

    fun evaluate(
        installedAndVerified: Boolean,
        expected: List<ExpectedModelBlob>,
        revision: String,
        remote: List<RemoteModelBlob>,
    ): ModelUpdateResult {
        if (!revisionPattern.matches(revision) || expected.isEmpty() ||
            expected.any { !sha256Pattern.matches(it.sha256) || it.bytes != null && it.bytes <= 0L } ||
            remote.any { !sha256Pattern.matches(it.sha256) || it.bytes <= 0L }) {
            return ModelUpdateResult(ModelUpdateState.UNVERIFIABLE,
                "Удалённый каталог не прошёл проверку ревизии и SHA-256.")
        }
        val byName = remote.groupBy { it.filename }
        val selected = expected.map { entry ->
            byName[entry.filename]?.singleOrNull()
                ?: return ModelUpdateResult(ModelUpdateState.UNVERIFIABLE,
                    "В каталоге отсутствует проверяемый файл модели.")
        }
        val total = selected.sumOf { it.bytes }
        val differences = expected.zip(selected).mapNotNull { (pin, latest) ->
            val fields = mutableListOf<String>()
            if (pin.sha256 != latest.sha256) fields.add("SHA-256")
            if (pin.bytes != null && pin.bytes != latest.bytes)
                fields.add("размер ${pin.bytes} → ${latest.bytes} байт")
            if (fields.isEmpty()) null else "${pin.filename}: ${fields.joinToString(", ")}"
        }
        val changed = differences.isNotEmpty()
        val revisionShort = revision.take(12)
        val megabytes = "%.1f".format(java.util.Locale.US, total / 1_000_000.0)
        return when {
            changed -> ModelUpdateResult(ModelUpdateState.REVIEW_REQUIRED,
                "Новая ревизия $revisionShort ($megabytes МБ). Изменения: " +
                    "${differences.joinToString("; ")}. Требуется проверка лицензии, " +
                    "совместимости и утверждение перед загрузкой.",
                revision, total)
            !installedAndVerified -> ModelUpdateResult(ModelUpdateState.NOT_INSTALLED,
                "Опубликованная версия $revisionShort ($megabytes МБ) совпадает с проверенным каталогом, " +
                    "но локальная установка отсутствует или повреждена.",
                revision, total)
            else -> ModelUpdateResult(ModelUpdateState.CURRENT,
                "Установленные файлы проверены по SHA-256. Опубликованная версия " +
                    "$revisionShort ($megabytes МБ) совпадает с проверенным каталогом.",
                revision, total)
        }
    }
}

/**
 * Explicit HTTPS-only metadata check against the model publisher's Hugging Face repository.
 * The server response is not a trusted installation manifest: all proposed bytes are
 * compared against immutable code-reviewed SHA-256 pins. Different weights are reported
 * for manual review; install() keeps using the existing pins and never runs here.
 */
internal object AiModelUpdateChecker {
    internal enum class Kind { LAMA, MOBILE_SAM, PEOPLE }

    private data class ModelSource(val repo: String, val expected: List<ExpectedModelBlob>)

    private fun source(kind: Kind): ModelSource = when (kind) {
        Kind.LAMA -> ModelSource("sapienkit/LaMa-ONNX",
            listOf(ExpectedModelBlob("lama_fp32.onnx", LaMaModelStore.SHA256)))
        Kind.PEOPLE -> ModelSource("litert-community/efficientdet", listOf(
            ExpectedModelBlob(PersonDetectorModelStore.FILENAME,
                PersonDetectorModelStore.SHA256),
        ))
        Kind.MOBILE_SAM -> ModelSource("Acly/MobileSAM", listOf(
            ExpectedModelBlob("mobile_sam_image_encoder.onnx",
                MobileSamModelStore.ENCODER_SHA256, MobileSamModelStore.ENCODER_BYTES),
            ExpectedModelBlob("sam_mask_decoder_single.onnx",
                MobileSamModelStore.DECODER_SHA256, MobileSamModelStore.DECODER_BYTES),
        ))
    }

    suspend fun check(kind: Kind, installedAndVerified: Boolean): ModelUpdateResult =
        withContext(Dispatchers.IO) {
            try {
                val spec = source(kind)
                val manifest = fetchMetadata(spec.repo)
                val root = JSONObject(manifest)
                if (root.optString("id") != spec.repo)
                    throw IOException("Источник каталога не совпадает с ожидаемым.")
                val revision = root.optString("sha")
                val siblings = root.optJSONArray("siblings")
                    ?: throw IOException("Каталог не содержит список файлов.")
                val remote = mutableListOf<RemoteModelBlob>()
                for (i in 0 until siblings.length()) {
                    val entry = siblings.optJSONObject(i) ?: continue
                    val name = entry.optString("rfilename")
                    if (spec.expected.none { it.filename == name }) continue
                    // A filename alone is not evidence. Require published LFS SHA and byte size.
                    val lfs = entry.optJSONObject("lfs")
                        ?: throw IOException("У файла $name нет проверяемого SHA-256.")
                    val digest = lfs.optString("sha256").lowercase(java.util.Locale.ROOT)
                    val bytes = lfs.optLong("size", -1L)
                    remote.add(RemoteModelBlob(name, digest, bytes))
                }
                ModelUpdatePolicy.evaluate(installedAndVerified, spec.expected, revision, remote)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                ModelUpdateResult(ModelUpdateState.UNAVAILABLE,
                    "Не удалось достоверно проверить обновления. Проверьте сеть или источник моделей; " +
                        "состояние «обновлений нет» не подтверждено.")
            }
        }

    private suspend fun fetchMetadata(repo: String): String {
        val url = URL("https://huggingface.co/api/models/$repo?blobs=true")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK)
                throw IOException("Каталог недоступен: HTTP ${connection.responseCode}")
            val contentType = connection.contentType.orEmpty()
            if (!contentType.contains("json", ignoreCase = true))
                throw IOException("Ответ не является JSON.")
            val buffer = ByteArray(8192)
            val output = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = stream.read(buffer)
                    if (n < 0) break
                    if (output.size() + n > 1_048_576)
                        throw IOException("Каталог моделей превышает допустимый размер.")
                    output.write(buffer, 0, n)
                }
            }
            return output.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }
}
