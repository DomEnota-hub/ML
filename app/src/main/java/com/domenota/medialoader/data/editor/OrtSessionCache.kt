package com.domenota.medialoader.data.editor

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import com.domenota.medialoader.core.logging.AppLog
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A single ONNX Runtime session per installed model. The lock covers inference,
 * warm-up and close: trim-memory/installation never close an in-flight session.
 * Models live only in private app storage; never persist user input tensors.
 */
internal class CachedOrtSession(private val kind: OrtTuning.Model) {
    private val mutex = Mutex()
    private var active: OrtSession? = null
    private var fileStamp: String? = null
    private var contentSha256: String? = null

    suspend fun <T> use(model: File, block: suspend (OrtSession) -> T): T =
        mutex.withLock { block(getOrOpen(model)) }

    suspend fun warmUp(model: File) {
        mutex.withLock { getOrOpen(model) }
    }

    suspend fun close() {
        mutex.withLock {
            active?.close()
            active = null
            fileStamp = null
            contentSha256 = null
        }
    }

    private fun getOrOpen(model: File): OrtSession {
        if (!model.isFile || model.length() == 0L) throw IOException(
            "Модель ${kind.name} не установлена.")
        val stamp = model.canonicalPath + "|" + model.length() + "|" + model.lastModified()
        active?.let { if (fileStamp == stamp) return it }
        active?.close()
        active = null
        fileStamp = null
        contentSha256 = null
        // Digest only on a cold or changed model, not on each processing request.
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val started = SystemClock.elapsedRealtimeNanos()
        val created = OrtTuning.options(kind).use { options ->
            OrtEnvironment.getEnvironment().createSession(model.absolutePath, options)
        }
        active = created
        fileStamp = stamp
        contentSha256 = hash
        val elapsedMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000
        AppLog.i("OrtPerf", "session-init model=${kind.name} coldMs=$elapsedMs")
        return created
    }
}

internal object OrtTuning {
    internal enum class Model { LAMA, SAM_ENCODER, SAM_DECODER, SRNET, DEXNET }

    /** Benchmark-only overrides. Safe baseline remains 2 threads and CPU. */
    @Volatile var experimentalThreadCount: Int? = null
    @Volatile var experimentalXnnpack: Set<Model> = emptySet()

    fun options(model: Model): OrtSession.SessionOptions {
        val options = OrtSession.SessionOptions()
        val xnnpack = model in experimentalXnnpack
        if (xnnpack) {
            // XNNPACK uses its own thread pool; avoid two contending pools.
            options.setIntraOpNumThreads(1)
            options.addConfigEntry("session.intra_op.allow_spinning", "0")
            try {
                options.addXnnpack(mapOf(
                    "intra_op_num_threads" to (experimentalThreadCount ?: 2).coerceIn(1, 4).toString()))
            } catch (_: Exception) {
                // Some devices do not have the EP; CPU baseline remains available.
            }
        } else {
            options.setIntraOpNumThreads((experimentalThreadCount ?: 2).coerceIn(1, 4))
        }
        if (model == Model.DEXNET) {
            // Preserve the original DExNet memory and graph contract.
            options.setCPUArenaAllocator(false)
            options.setMemoryPatternOptimization(false)
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
        }
        return options
    }
}

/**
 * Editor lifecycle owns prewarming, not individual inference requests. Closing
 * the editor cancels warm-up and schedules a delayed release. Re-enter cancels
 * that release; Android memory pressure may release sessions immediately.
 * Session mutexes always protect in-flight work.
 */
internal object OrtSessions {
    val lama = CachedOrtSession(OrtTuning.Model.LAMA)
    val samEncoder = CachedOrtSession(OrtTuning.Model.SAM_ENCODER)
    val samDecoder = CachedOrtSession(OrtTuning.Model.SAM_DECODER)
    val srnet = CachedOrtSession(OrtTuning.Model.SRNET)
    val dexnet = CachedOrtSession(OrtTuning.Model.DEXNET)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val guard = Any()
    private var openPanels = 0
    private var warmJob: Job? = null
    private var idleJob: Job? = null
    private var registered = false

    fun editorEntered(context: Context, models: LaMaModelStore) {
        synchronized(guard) {
            openPanels++
            idleJob?.cancel()
            idleJob = null
            if (!registered) {
                registered = true
                context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
                    override fun onConfigurationChanged(newConfig: Configuration) = Unit
                    override fun onLowMemory() = releaseForPressure()
                    override fun onTrimMemory(level: Int) {
                        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) releaseForPressure()
                    }
                })
            }
            warmJob?.cancel()
            warmJob = scope.launch {
                try {
                    if (models.installed()) lama.warmUp(models.requireInstalled())
                } catch (_: Exception) {
                    // Best-effort. The actual request still reports any error.
                }
            }
        }
    }

    fun editorExited() {
        synchronized(guard) {
            openPanels = (openPanels - 1).coerceAtLeast(0)
            if (openPanels != 0) return
            warmJob?.cancel()
            idleJob?.cancel()
            idleJob = scope.launch {
                delay(12_000L)
                val shouldRelease = synchronized(guard) { openPanels == 0 }
                if (shouldRelease) releaseAll()
            }
        }
    }

    fun releaseForPressure() {
        synchronized(guard) { warmJob?.cancel() }
        scope.launch { releaseAll() }
    }

    suspend fun releaseAll() {
        lama.close()
        samEncoder.close()
        samDecoder.close()
        srnet.close()
        dexnet.close()
    }

    /** Run after deleting or replacing the model; mutex blocks until inference ends. */
    fun invalidateLaMa() {
        scope.launch { lama.close() }
    }
}
