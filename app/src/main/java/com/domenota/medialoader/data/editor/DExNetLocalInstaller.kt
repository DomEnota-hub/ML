package com.domenota.medialoader.data.editor

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Pinned development installer. No remotely executable code or combined weights. */
internal object DExNetLocalInstaller {
    private const val ROOT = "https://raw.githubusercontent.com/DomEnota-hub/ML-models/4b284dacd842dcaddb746d6e6b3490ac08cb6f49/dexnet/v1/"
    private const val MAP_BYTES = 292_641L
    private const val MAP_SHA = "94facb4a6c5aba78ea8d97125cd726cc66a48f55f3ef09116c4723ad8e1ac20b"
    private const val GRAPH_BYTES = 97_846L
    private const val GRAPH_SHA = "08599b24566da57726b5a2f46245489acf2ca5c261271dc43f68ce8f48b4c953"
    private const val SKELETON_BYTES = 1_548_204L
    private const val SKELETON_SHA = "91f8bd75de65b1dc695131ca27d7ccb4437a095482f5307885df8731edf7ed53"
    private const val DEXNET_URL = "https://drive.usercontent.google.com/download?id=1jkhCvyrQAQZtLaxdDWDkkSOF0NZ12aJp&export=download&confirm=t"
    private const val VGG_URL = "https://download.pytorch.org/models/vgg19-dcbb9e9d.pth"
    private val dex = AssemblySource("dexnet", 118_442_981L, "b58316c3cb339c0b334b83d5855446d19e5d57c49f88bc3b529b0da54498bcd3")
    private val vgg = AssemblySource("vgg19", 574_673_361L, "dcbb9e9dad569fff7a846263a77324fc34978fea2bfb039c012d710e1776ae44")
    const val MIN_FREE_BYTES = 900_000_000L

    private fun parsePlan(text: String): ModelAssemblyPlan {
        val root = JSONObject(text)
        if (root.getInt("schemaVersion") != 1 || root.getString("model") != "dexnet-vgg19-128" ||
            root.getLong("compressedBytes") != GRAPH_BYTES || root.getString("compressedSha256") != GRAPH_SHA)
            throw IOException("Схема DExNet несовместима.")
        val sourceArray = root.getJSONArray("sources")
        if (sourceArray.length() != 2) throw IOException("Неверные компоненты DExNet.")
        val sources = List(sourceArray.length()) { index ->
            val item = sourceArray.getJSONObject(index)
            AssemblySource(item.getString("id"), item.getLong("bytes"), item.getString("sha256"))
        }
        if (sources != listOf(dex, vgg)) throw IOException("Изменились исходные компоненты DExNet.")
        val ranges = root.getJSONArray("ranges")
        if (ranges.length() != 1854) throw IOException("Неверная карта тензоров DExNet.")
        val plan = ModelAssemblyPlan(root.getLong("outputBytes"), root.getString("outputSha256"),
            root.getLong("skeletonBytes"), root.getString("skeletonSha256"), sources,
            List(ranges.length()) { index ->
                val r = ranges.getJSONObject(index)
                AssemblyRange(r.getLong("outputOffset"), r.getString("source"), r.getLong("sourceOffset"),
                    r.getLong("length"), r.getString("sha256"))
            })
        if (plan.outputBytes != ReflectionDExModelStore.EXACT_BYTES ||
            plan.outputSha256 != ReflectionDExModelStore.EXPECTED_SHA256 ||
            plan.skeletonBytes != SKELETON_BYTES || plan.skeletonSha256 != SKELETON_SHA)
            throw IOException("Схема DExNet не соответствует проверенной модели.")
        ModelRangeAssembler.validate(plan)
        return plan
    }

    suspend fun build(staging: File, onProgress: (Int) -> Unit): File {
        val context = currentCoroutineContext()
        val work = MAP_BYTES + GRAPH_BYTES + dex.bytes + vgg.bytes + ReflectionDExModelStore.EXACT_BYTES
        var completed = 0L
        var lastPercent = -1
        fun report(bytes: Long) {
            val percent = ((completed + bytes) * 100 / work).toInt().coerceIn(0, 99)
            if (percent != lastPercent) { lastPercent = percent; onProgress(percent) }
        }
        fun fetch(url: String, bytes: Long, hash: String, name: String, hostAllowed: (String) -> Boolean): File {
            val target = File(staging, name)
            downloadPinned(url, bytes, hash, target, hostAllowed, { context.ensureActive() }, ::report)
            completed += bytes
            return target
        }
        val github: (String) -> Boolean = { it == "raw.githubusercontent.com" }
        val google: (String) -> Boolean = { it == "drive.usercontent.google.com" || it == "drive.google.com" ||
            it.endsWith(".googleusercontent.com") }
        val map = fetch(ROOT + "ranges.json", MAP_BYTES, MAP_SHA, "ranges.json", github)
        val plan = parsePlan(map.readText(Charsets.UTF_8))
        val graph = fetch(ROOT + "graph.skeleton.gz", GRAPH_BYTES, GRAPH_SHA, "graph.gz", github)
        val dexFile = fetch(DEXNET_URL, dex.bytes, dex.sha256, "dexnet.pt", google)
        val vggFile = fetch(VGG_URL, vgg.bytes, vgg.sha256, "vgg19.pth") { it == "download.pytorch.org" }
        val output = File(staging, ReflectionDExModelStore.MODEL_NAME)
        GZIPInputStream(graph.inputStream().buffered()).use { skeleton ->
            ModelRangeAssembler.assemble(plan, skeleton, mapOf(dex.id to dexFile, vgg.id to vggFile),
                output, { context.ensureActive() }, ::report)
        }
        context.ensureActive()
        return output
    }

    private fun downloadPinned(
        address: String, bytes: Long, sha256: String, target: File, hostAllowed: (String) -> Boolean,
        checkCancelled: () -> Unit, progress: (Long) -> Unit,
    ) {
        var url = URL(address)
        var redirects = 0
        var success = false
        if (!target.createNewFile()) throw IOException("Временный компонент модели уже существует.")
        try {
            while (true) {
                checkCancelled()
                val host = url.host.lowercase(Locale.ROOT)
                if (url.protocol != "https" || url.userInfo != null || url.port !in listOf(-1, 443) ||
                    !hostAllowed(host)) throw IOException("Неподдерживаемый адрес компонента модели.")
                val connection = url.openConnection() as HttpURLConnection
                try {
                    connection.instanceFollowRedirects = false
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 25_000
                    connection.setRequestProperty("Accept-Encoding", "identity")
                    connection.setRequestProperty("User-Agent", "MediaLoader/ModelInstaller")
                    val code = connection.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        if (++redirects > 5) throw IOException("Слишком много перенаправлений модели.")
                        val location = connection.getHeaderField("Location") ?: throw IOException("Нет адреса модели.")
                        url = URL(url, location)
                        continue
                    }
                    if (code != HttpURLConnection.HTTP_OK)
                        throw IOException("Источник модели недоступен (HTTP $code).")
                    if (connection.contentLengthLong >= 0 && connection.contentLengthLong != bytes)
                        throw IOException("Размер компонента модели изменился.")
                    val digest = ModelRangeAssembler.digest()
                    var total = 0L
                    connection.inputStream.use { input ->
                        FileOutputStream(target).use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                checkCancelled()
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (n == 0 || total + n > bytes) throw IOException("Неверный размер компонента.")
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                total += n
                                progress(total)
                            }
                            out.fd.sync()
                        }
                    }
                    checkCancelled()
                    if (total != bytes || ModelRangeAssembler.hex(digest.digest()) != sha256)
                        throw IOException("Компонент модели не прошёл SHA-256.")
                    success = true
                    return
                } finally { connection.disconnect() }
            }
        } finally { if (!success) target.delete() }
    }
}
