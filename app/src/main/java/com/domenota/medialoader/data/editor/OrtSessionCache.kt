package com.domenota.medialoader.data.editor

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Debug
import android.os.SystemClock
import android.os.StatFs
import com.domenota.medialoader.core.logging.AppLog
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stage 1: sessions stay alive between requests, while each use/release is
 * serialized. OrtSession.SessionOptions stays alive for its entire session.
 * Never share an OrtSession with another thread without this gate.
 */
internal enum class OrtModelRole { LAMA, SAM_ENCODER, SAM_DECODER, SRNET, DEXNET }

internal data class OrtSessionHandle(
    val session: OrtSession,
    val options: OrtSession.SessionOptions,
) : AutoCloseable {
    override fun close() {
        try { session.close() } finally { options.close() }
    }
}

internal class CachedOrtSession(private val role: OrtModelRole) {
    private val cache = SerializedModelCache<OrtSessionHandle>()

    suspend fun <T> use(
        model: File, pinnedSha: String? = null, block: suspend (OrtSession) -> T,
    ): T = cache.use(
        key = {
            if (!model.isFile || model.length() <= 0L) throw IOException("ONNX: модель отсутствует.")
            listOf(model.absolutePath, model.length(), model.lastModified(), pinnedSha.orEmpty(),
                role.name, OrtTuning.settingsKey(role)).joinToString("|")
        },
        open = {
            val start = SystemClock.elapsedRealtime()
            val created = OrtTuning.open(model, role, pinnedSha)
            AppLog.i("AiOrtPerf", "model=${role.name} session_init_ms=${SystemClock.elapsedRealtime()-start} " +
                "model_bytes=${model.length()} native_heap_bytes=${Debug.getNativeHeapAllocatedSize()}")
            created
        },
    ) { handle, reused ->
        if (role == OrtModelRole.LAMA) AppLog.i("AiOrtPerf", "model=${role.name} session_reused=$reused")
        block(handle.session)
    }

    suspend fun warmUp(model: File, pinnedSha: String? = null) = use(model, pinnedSha) { Unit }

    suspend fun release(reason: String = "explicit") = releaseIf(reason) { true }

    suspend fun releaseIf(reason: String, predicate: () -> Boolean) {
        if (cache.releaseIf(predicate)) AppLog.i("AiOrtPerf", "model=${role.name} session_released=$reason")
    }

    // Used by LaMa install/uninstall so another request cannot reopen the
    // old model between releasing its handle and changing the file on disk.
    suspend fun <T> mutateModel(block: () -> T): T = cache.mutate(block)
}

internal object OrtSessions {
    val lama = CachedOrtSession(OrtModelRole.LAMA)
    val samEncoder = CachedOrtSession(OrtModelRole.SAM_ENCODER)
    val samDecoder = CachedOrtSession(OrtModelRole.SAM_DECODER)
    val srnet = CachedOrtSession(OrtModelRole.SRNET)
    val dexnet = CachedOrtSession(OrtModelRole.DEXNET)
    private val all = listOf(lama, samEncoder, samDecoder, srnet, dexnet)
    private val activeEditors = AtomicInteger(0)
    private val generation = AtomicLong(0)
    private val lifecycleLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var idleJob: Job? = null
    @Volatile private var appContext: Context? = null
    @Volatile private var callbacksRegistered = false

    /** Reference-count ownership; an old idle task cannot evict a new editor. */
    fun attachEditor(context: Context) {
        val app = context.applicationContext
        installMemoryCallbacks(app)
        synchronized(lifecycleLock) {
            appContext = app
            activeEditors.incrementAndGet()
            generation.incrementAndGet()
            idleJob?.cancel()
            idleJob = null
        }
    }

    fun detachEditor() {
        synchronized(lifecycleLock) {
            if (activeEditors.get() == 0) return
            if (activeEditors.decrementAndGet() != 0) return
            val token = generation.incrementAndGet()
            idleJob?.cancel()
            idleJob = scope.launch {
                val stillIdle = { LaMaIdlePolicy.mayRelease(activeEditors.get(), generation.get(), token) }
                // Other models retain their existing immediate idle eviction.
                // LaMa eviction is independent of their in-flight inference.
                launch {
                    for (cache in all.filter { it !== lama }) cache.releaseIf("editor_idle", stillIdle)
                }
                val grace = memoryGraceMillis()
                if (grace > 0) delay(grace)
                lama.releaseIf(if (grace > 0) "editor_idle" else "editor_idle_low_memory", stillIdle)
            }
        }
    }

    private fun memoryGraceMillis(): Long = try {
        val manager = appContext?.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val info = ActivityManager.MemoryInfo()
        if (manager == null) 0L else {
            manager.getMemoryInfo(info)
            LaMaIdlePolicy.graceMillis(info.lowMemory, info.availMem, info.threshold)
        }
    } catch (_: Exception) { 0L }

    fun trimMemory() {
        scope.launch { for (cache in all) cache.release("memory_pressure") }
    }

    private fun installMemoryCallbacks(context: Context) {
        if (callbacksRegistered) return
        synchronized(this) {
            if (callbacksRegistered) return
            context.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = Unit
                override fun onLowMemory() = trimMemory()
                override fun onTrimMemory(level: Int) {
                    if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
                        level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) trimMemory()
                }
            })
            callbacksRegistered = true
        }
    }
}

/**
 * Explicitly gated tunables. Preserve CPU execution and the original
 * DExNet BASIC_OPT/allocator behavior until device A/B says otherwise.
 * Never auto-download, modify or silently replace model weights.
 */
internal object OrtTuning {
    private const val ORT_LAYOUT_VERSION = "ort-1.20-arm64-v1"
    // Independent flags per model. All are OFF until real Xiaomi benchmarks.
    const val ENABLE_LAMA_XNNPACK = false
    const val ENABLE_SAM_XNNPACK = false
    const val ENABLE_SRNET_XNNPACK = false
    const val ENABLE_DEXNET_XNNPACK = false
    const val ENABLE_OPTIMIZED_GRAPH_CACHE = false
    // 2 is the proven baseline. Test 3 / 4 on-device without inferring core topology.
    const val BASELINE_CPU_THREADS = 2

    private fun useXnnpack(role: OrtModelRole) = when (role) {
        OrtModelRole.LAMA -> ENABLE_LAMA_XNNPACK
        OrtModelRole.SAM_ENCODER, OrtModelRole.SAM_DECODER -> ENABLE_SAM_XNNPACK
        OrtModelRole.SRNET -> ENABLE_SRNET_XNNPACK
        OrtModelRole.DEXNET -> ENABLE_DEXNET_XNNPACK
    }

    fun settingsKey(role: OrtModelRole) =
        "${role.name}|$ORT_LAYOUT_VERSION|$BASELINE_CPU_THREADS|${useXnnpack(role)}|$ENABLE_OPTIMIZED_GRAPH_CACHE"

    fun open(model: File, role: OrtModelRole, pinnedSha: String?): OrtSessionHandle {
        val began = SystemClock.elapsedRealtime()
        val env = OrtEnvironment.getEnvironment()
        val environmentReady = SystemClock.elapsedRealtime()
        val options = OrtSession.SessionOptions()
        try {
            val xnnpack = useXnnpack(role)
            options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
            options.setIntraOpNumThreads(if (xnnpack) 1 else BASELINE_CPU_THREADS)
            if (role == OrtModelRole.DEXNET) {
                options.setCPUArenaAllocator(false)
                options.setMemoryPatternOptimization(false)
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.BASIC_OPT)
            } else {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            if (xnnpack) {
                options.addConfigEntry("session.intra_op.allow_spinning", "0")
                options.addXnnpack(mapOf("intra_op_num_threads" to BASELINE_CPU_THREADS.toString()))
            }
            // An optimized graph is derived data. Its file name is tied to the
            // pinned source SHA, ORT ABI/config and model role. No unsafe
            // metadata-only cache is created when a verified SHA is unknown.
            val graph = if (ENABLE_OPTIMIZED_GRAPH_CACHE && !xnnpack &&
                !pinnedSha.isNullOrBlank() && pinnedSha.matches(Regex("[0-9a-f]{64}")) &&
                role != OrtModelRole.DEXNET) {
                File(model.parentFile, "${model.name}.$pinnedSha.$ORT_LAYOUT_VERSION.${role.name}.opt.onnx")
            } else null
            if (graph != null && !graph.isFile) {
                val free = StatFs(model.parentFile.absolutePath).availableBytes
                if (free > model.length() * 2L) {
                    // Set only if enough space; cold starts without space
                    // remain safe and use the original verified model.
                    options.setOptimizedModelFilePath(graph.absolutePath)
                }
            }
            val chosen = graph?.takeIf { it.isFile && it.length() > 0L } ?: model
            val optionsReady = SystemClock.elapsedRealtime()
            val session = env.createSession(chosen.absolutePath, options)
            AppLog.i("AiOrtPerf", "model=${role.name} provider=${if (xnnpack) "XNNPACK" else "CPU"} " +
                "threads=$BASELINE_CPU_THREADS environment_ms=${environmentReady-began} " +
                "options_ms=${optionsReady-environmentReady} ort_create_ms=${SystemClock.elapsedRealtime()-optionsReady} " +
                "optimized_graph=${chosen !== model}")
            return OrtSessionHandle(session, options)
        } catch (error: Throwable) {
            options.close()
            throw error
        }
    }
}
