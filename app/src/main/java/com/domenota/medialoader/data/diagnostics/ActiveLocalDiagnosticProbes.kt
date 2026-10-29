package com.domenota.medialoader.data.diagnostics

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import com.domenota.medialoader.core.logging.AppLog
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room.Room
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticEvidence
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.DiagnosticScope
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.HistoryRepository
import com.domenota.medialoader.data.download.ActiveDownloadService
import com.domenota.medialoader.data.download.DownloadEngine
import com.domenota.medialoader.data.download.DownloadQueue
import com.domenota.medialoader.data.download.DownloadResult
import com.domenota.medialoader.data.download.ForegroundServiceObservation
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.storage.StorageManager
import com.domenota.medialoader.data.storage.StorageSettings
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Functional probes run only after the user explicitly starts full diagnostics. */
object ActiveLocalDiagnosticProbes {
    fun specifications(): List<DiagnosticCheckSpec> = listOf(
        spec("runtime.ytdlp.active", DiagnosticComponent.MEDIA_RUNTIME, 90_000),
        spec("runtime.ffmpeg.active", DiagnosticComponent.MEDIA_RUNTIME, 90_000),
        spec("runtime.quickjs.active", DiagnosticComponent.MEDIA_RUNTIME, 15_000),
        spec("queue.synthetic", DiagnosticComponent.DOWNLOAD_QUEUE, 15_000),
        spec("queue.service.active", DiagnosticComponent.DOWNLOAD_QUEUE, 10_000),
        spec("storage.write", DiagnosticComponent.STORAGE, 20_000),
        spec("editor.onnx.active", DiagnosticComponent.PHOTO_EDITOR, 90_000),
    )

    fun probes(context: Context): List<DiagnosticProbe> {
        val app = context.applicationContext
        return specifications().map { check ->
            object : DiagnosticProbe {
                override val spec = check
                override suspend fun check(): DiagnosticOutcome = when (check.code) {
                    "runtime.ytdlp.active" -> withContext(Dispatchers.IO) {
                        try {
                            YoutubeDL.getInstance().init(app)
                            val version = YoutubeDL.getInstance().version(app)
                            if (version.isNullOrBlank()) limited("yt-dlp запущен, но версия не получена.",
                                "YTDLP_VERSION_UNKNOWN")
                            else ok("yt-dlp инициализирован и ответил на запрос версии. Извлечение материала проверяется отдельно.",
                                "YTDLP_STARTED")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed("Не удалось запустить yt-dlp.", "YTDLP_START_FAILED") }
                    }
                    "runtime.ffmpeg.active" -> withContext(Dispatchers.IO) {
                        try {
                            FFmpeg.getInstance().init(app)
                            ok("FFmpeg инициализирован. Обработка файла не выполнялась.", "FFMPEG_STARTED")
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { failed("Не удалось инициализировать FFmpeg.", "FFMPEG_START_FAILED") }
                    }
                    "runtime.quickjs.active" -> withContext(Dispatchers.IO) {
                        try {
                            System.loadLibrary("qjs")
                            val executable = java.io.File(app.applicationInfo.nativeLibraryDir, "libqjs.so")
                            if (!executable.isFile) return@withContext failed(
                                "Исполняемый файл QuickJS отсутствует.", "QUICKJS_LOAD_FAILED")
                            var process: Process? = null
                            try {
                                process = ProcessBuilder(executable.absolutePath, "-e", "print(6*7)")
                                    .redirectErrorStream(true).start()
                                val done = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
                                if (!done) {
                                    AppLog.i("Diagnostics", "QuickJS probe · stage=execution · timeout=true")
                                    limited("Скрипт QuickJS не завершился за 3 секунды.", "QUICKJS_EXEC_TIMEOUT")
                                } else {
                                    val output = process.inputStream.bufferedReader().use { it.readText().take(256) }
                                    val passed = process.exitValue() == 0 && output.trim() == "42"
                                    AppLog.i("Diagnostics", "QuickJS probe · exit=${process.exitValue()} · expected=$passed")
                                    if (passed) ok("QuickJS выполнил контрольный скрипт. Использование движка yt-dlp проверяется отдельно.",
                                        "QUICKJS_EXEC_OK")
                                    else failed("QuickJS запустился, но не вернул ожидаемый результат.",
                                        "QUICKJS_EXEC_FAILED")
                                }
                            } catch (_: Exception) {
                                limited("Android не позволил запустить контрольный скрипт QuickJS.",
                                    "QUICKJS_EXEC_UNAVAILABLE")
                            } finally { process?.destroyForcibly() }
                        } catch (_: LinkageError) { failed("QuickJS не удалось загрузить.", "QUICKJS_LOAD_FAILED") }
                    }
                    "queue.synthetic" -> syntheticQueue(app)
                    "queue.service.active" -> service(app)
                    "storage.write" -> storage(app)
                    "editor.onnx.active" -> onnx(app)
                    else -> error("Unknown active diagnostic")
                }
            }
        }
    }

    private fun spec(code: String, component: DiagnosticComponent, timeout: Long) =
        DiagnosticCheckSpec(code, component, DiagnosticImpact.OPTIONAL,
            DiagnosticScope.FULL, timeoutMillis = timeout, maxAgeMillis = 5 * 60_000)

    private fun ok(message: String, reason: String) =
        DiagnosticOutcome(DiagnosticState.OK, DiagnosticEvidence.VERIFIED, message, reason)
    private fun limited(message: String, reason: String) =
        DiagnosticOutcome(DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED, message, reason)
    private fun failed(message: String, reason: String) =
        DiagnosticOutcome(DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED, message, reason)

    internal fun storageResult(readBack: Boolean, cleanupSucceeded: Boolean): DiagnosticOutcome =
        when {
            !readBack -> failed("Пробный файл записан, но не прочитан обратно.", "STORAGE_READ_FAILED")
            !cleanupSucceeded -> DiagnosticOutcome(DiagnosticState.WARNING,
                DiagnosticEvidence.VERIFIED,
                "Пробный файл записан и прочитан, но удалить его после проверки не удалось.",
                "STORAGE_CLEANUP_FAILED")
            else -> ok("Пробный файл записан, прочитан и удалён из папки загрузок.",
                "STORAGE_WRITE_OK")
        }

    private suspend fun syntheticQueue(app: Context): DiagnosticOutcome = withContext(Dispatchers.IO) {
        val database = Room.inMemoryDatabaseBuilder(app, HistoryDatabase::class.java).build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val history = HistoryRepository(database.downloadDao())
            val engine = object : DownloadEngine {
                override suspend fun download(task: com.domenota.medialoader.core.model.DownloadTask,
                    onProgress: suspend (Long, Long?) -> Unit): DownloadResult {
                    onProgress(1, 1)
                    return DownloadResult("diagnostic://local", 1)
                }
            }
            val queue = DownloadQueue(history, engine, existingNames = { emptySet() }, scope = scope)
            val row = queue.enqueue(listOf(MediaItem(UUID.randomUUID().toString(), "diagnostic",
                MediaType.PHOTO, "diagnostic.jpg", "diagnostic://local", sizeBytes = 1))).single()
            val completed = withTimeout(5_000) {
                history.observeAll().first { rows -> rows.any {
                    it.id == row.id && it.state in listOf(DownloadState.COMPLETED, DownloadState.FAILED)
                } }.first { it.id == row.id }
            }
            if (completed.state == DownloadState.COMPLETED)
                ok("Изолированная пробная задача прошла через механизм очереди. Основная очередь не изменена.",
                    "QUEUE_SYNTHETIC_COMPLETE")
            else failed("Изолированная пробная задача завершилась ошибкой.", "QUEUE_SYNTHETIC_FAILED")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            limited("Пробную задачу очереди не удалось завершить.", "QUEUE_SYNTHETIC_UNAVAILABLE")
        } finally {
            scope.cancel()
            database.close()
        }
    }

    private suspend fun service(app: Context): DiagnosticOutcome = try {
        ActiveDownloadService.start(app)
        withTimeout(7_000) {
            while (!ForegroundServiceObservation.isForegroundObserved()) delay(100)
        }
        ok("Фоновая служба запущена и вызвала startForeground. Долгая работа в фоне не проверялась.",
            "SERVICE_STARTED")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        limited("Не удалось подтвердить запуск фоновой службы в текущем процессе.",
            "SERVICE_START_UNAVAILABLE")
    }

    private suspend fun storage(app: Context): DiagnosticOutcome = withContext(Dispatchers.IO) {
        val sample = File.createTempFile("diagnostic-", ".txt", app.cacheDir)
        var published: Uri? = null
        var readBack = false
        var writeFailed = false
        var cleanupSucceeded = true
        try {
            sample.writeText("MediaLoader diagnostic")
            published = StorageManager(app, StorageSettings(app)).publish(sample,
                "MediaLoader-diagnostic-${UUID.randomUUID()}.txt", "text/plain").uri
            readBack = app.contentResolver.openInputStream(published)?.use { input ->
                input.readBytes().toString(Charsets.UTF_8)
            } == "MediaLoader diagnostic"
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            writeFailed = true
        } finally {
            published?.let { uri ->
                cleanupSucceeded = when (uri.scheme) {
                    "file" -> File(uri.path.orEmpty()).delete()
                    "content" -> {
                        val deleted = runCatching { app.contentResolver.delete(uri, null, null) }
                            .getOrDefault(0)
                        deleted > 0 || runCatching {
                            DocumentFile.fromSingleUri(app, uri)?.delete() == true
                        }.getOrDefault(false)
                    }
                    else -> false
                }
            }
            if (!sample.delete()) cleanupSucceeded = false
        }
        if (writeFailed) failed("Не удалось записать пробный файл в папку загрузок.",
            "STORAGE_WRITE_FAILED")
        else storageResult(readBack, cleanupSucceeded)
    }

    private suspend fun onnx(app: Context): DiagnosticOutcome = withContext(Dispatchers.IO) {
        try {
            val environment = OrtEnvironment.getEnvironment()
            val model = LaMaModelStore(app)
            if (!model.installed()) return@withContext limited(
                "ONNX Runtime запущен, но модель LaMa не установлена; сеанс не создавался.",
                "ONNX_MODEL_MISSING")
            OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                environment.createSession(model.requireInstalled().absolutePath, options).use { session ->
                    if (session.inputNames.isEmpty())
                        failed("Сеанс ONNX создан без ожидаемых входов.", "ONNX_INPUT_MISSING")
                    else ok("Сеанс ONNX с моделью LaMa создан. Обработка изображения не выполнялась.",
                        "ONNX_SESSION_STARTED")
                }
            }
        } catch (_: LinkageError) { failed("Не удалось загрузить ONNX Runtime.", "ONNX_LOAD_FAILED") }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed("Не удалось создать сеанс ONNX Runtime.", "ONNX_SESSION_FAILED") }
    }
}
