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

/** Owner catalog is advisory until authenticated release activation exists.
 * No remote URL, version or digest is used to authorize installation.
 */
internal object OwnerModelCatalog {
    private const val URL_PATH = "https://raw.githubusercontent.com/DomEnota-hub/ML-models/main/catalog.json"
    private const val MAX_BYTES = 128 * 1024

    data class Blob(val name: String, val sha256: String, val bytes: Long?)
    data class Record(val id: String, val files: List<Blob>)

    fun compare(records: List<Record>, installed: Map<String, Boolean>): Map<String, ModelUpdateResult> {
        val known = AiModelCatalog.entries
        val wellFormed = records.map { it.id }.toSet() == known.map { it.kind.key }.toSet() &&
            records.size == known.size
        return known.associate { entry ->
            val id = entry.kind.key
            val row = records.singleOrNull { it.id == id }
            val files = row?.files.orEmpty()
            val valid = wellFormed && row != null && files.isNotEmpty() &&
                files.map { it.name }.distinct().size == files.size &&
                files.all { it.name.isNotBlank() && it.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    (it.bytes == null || it.bytes > 0L) }
            val expected = entry.files.map { pin ->
                val name = if (entry.kind == AiModelUpdateChecker.Kind.PEOPLE)
                    PersonDetectorModelStore.FILENAME else pin.filename.substringAfterLast('/')
                Blob(name, pin.sha256, pin.bytes)
            }
            val matchingNames = files.map { it.name }.toSet() == expected.map { it.name }.toSet()
            val matching = valid && matchingNames && expected.all { pin ->
                val other = files.single { it.name == pin.name }
                other.sha256 == pin.sha256 && (pin.bytes == null || pin.bytes == other.bytes)
            }
            val state = when {
                !valid -> ModelUpdateState.UNVERIFIABLE
                !matching -> ModelUpdateState.REVIEW_REQUIRED
                installed[id] == true -> ModelUpdateState.CURRENT
                else -> ModelUpdateState.NOT_INSTALLED
            }
            id to ModelUpdateResult(state, when (state) {
                ModelUpdateState.CURRENT -> "Каталог ML-models совпадает с закреплённой моделью."
                ModelUpdateState.NOT_INSTALLED -> "Версия доступна согласно текущему способу установки."
                ModelUpdateState.REVIEW_REQUIRED -> "Изменение каталога требует проверки; установка новых весов заблокирована."
                else -> "Каталог не прошёл проверку структуры."
            })
        }
    }

    suspend fun check(installed: Map<String, Boolean>): Map<String, ModelUpdateResult> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(URL_PATH).openConnection() as HttpURLConnection
            val body = try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 10_000
                connection.readTimeout = 15_000
                if (connection.responseCode != HttpURLConnection.HTTP_OK ||
                    connection.contentLengthLong > MAX_BYTES) throw IOException("Каталог недоступен.")
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_BYTES) throw IOException("Каталог слишком велик.")
                        output.write(buffer, 0, count)
                    }
                }
                output.toString(Charsets.UTF_8.name())
            } finally { connection.disconnect() }
            val root = JSONObject(body)
            if (root.getInt("schemaVersion") != 1 || root.getString("status") != "inventory-only" ||
                root.getBoolean("remoteUpdatesAuthorized")) throw IOException("Неподдерживаемый каталог.")
            val models = root.getJSONArray("models")
            if (models.length() != AiModelCatalog.entries.size) throw IOException("Неверный состав каталога.")
            val records = (0 until models.length()).map { index ->
                val row = models.getJSONObject(index)
                val files = row.getJSONArray("files")
                if (files.length() !in 1..8) throw IOException("Неверный состав модели.")
                Record(row.getString("id"), (0 until files.length()).map { fileIndex ->
                    val file = files.getJSONObject(fileIndex)
                    Blob(file.getString("name"), file.getString("sha256"),
                        if (file.isNull("bytes")) null else file.getLong("bytes"))
                })
            }
            compare(records, installed)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            AiModelCatalog.entries.associate { it.kind.key to ModelUpdateResult(ModelUpdateState.UNAVAILABLE,
                "Каталог ML-models недоступен; закреплённая установка сохранена.") }
        }
    }
}
