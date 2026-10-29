package com.domenota.medialoader.data.diagnostics

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.StatFs
import androidx.documentfile.provider.DocumentFile
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.diagnostics.DiagnosticCheckSpec
import com.domenota.medialoader.core.diagnostics.DiagnosticComponent
import com.domenota.medialoader.core.diagnostics.DiagnosticCoordinator
import com.domenota.medialoader.core.diagnostics.DiagnosticEvidence
import com.domenota.medialoader.core.diagnostics.DiagnosticImpact
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.DiagnosticProbe
import com.domenota.medialoader.core.diagnostics.DiagnosticState
import com.domenota.medialoader.core.diagnostics.LocalDiagnosticRules
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.download.ActiveDownloadService
import com.domenota.medialoader.data.download.ForegroundServiceObservation
import com.domenota.medialoader.data.editor.LaMaModelStore
import com.domenota.medialoader.data.storage.StorageSettings
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Stage 2: read-only Android adapters. Factory does not execute anything.
 * Invocation will be added to the diagnostic UI in the UI stage.
 *
 * None of these probes call yt-dlp.init, update, install LaMa, enqueue downloads,
 * open HTTP connections, create test files, delete files or change permissions.
 */
object LocalDiagnosticProbes {
    fun create(context: Context, repository: MediaRepository): DiagnosticCoordinator =
        DiagnosticCoordinator(probes(context, repository))

    /** Exposes read-only specs to the combined registry without performing checks. */
    fun probes(
        context: Context,
        repository: MediaRepository,
    ): List<DiagnosticProbe> {
        val app = context.applicationContext
        val rows: suspend () -> List<DownloadEntity> = { repository.downloads.first() }
        return listOf(
            classProbe(app, "runtime.ytdlp.library", "yt-dlp",
                "com.yausername.youtubedl_android.YoutubeDL"),
            classProbe(app, "runtime.ffmpeg.library", "FFmpeg",
                "com.yausername.ffmpeg.FFmpeg"),
            probe("runtime.quickjs.bundle", DiagnosticComponent.MEDIA_RUNTIME,
                DiagnosticImpact.OPTIONAL) {
                withContext(Dispatchers.IO) {
                    LocalDiagnosticRules.bundledQuickJs(
                        File(app.applicationInfo.nativeLibraryDir.orEmpty(), "libqjs.so").isFile)
                }
            },
            probe("queue.snapshot", DiagnosticComponent.DOWNLOAD_QUEUE,
                DiagnosticImpact.REQUIRED) {
                val items = rows()
                fun count(state: DownloadState) = items.count { it.state == state }
                LocalDiagnosticRules.queue(LocalDiagnosticRules.QueueSnapshot(
                    queued = count(DownloadState.QUEUED),
                    running = count(DownloadState.RUNNING),
                    completed = count(DownloadState.COMPLETED),
                    failed = count(DownloadState.FAILED),
                    cancelled = count(DownloadState.CANCELLED),
                ))
            },
            probe("queue.service_manifest", DiagnosticComponent.DOWNLOAD_QUEUE,
                DiagnosticImpact.REQUIRED) {
                val declared = try {
                    app.packageManager.getServiceInfo(
                        ComponentName(app, ActiveDownloadService::class.java), 0)
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                } catch (_: Exception) {
                    null
                }
                when (declared) {
                    true -> DiagnosticOutcome(
                        DiagnosticState.OK, DiagnosticEvidence.VERIFIED,
                        "Foreground-служба объявлена в пакете приложения. " +
                            "Это не доказывает её текущий запуск.",
                        "SERVICE_DECLARED")
                    false -> DiagnosticOutcome(
                        DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
                        "Foreground-служба загрузок не зарегистрирована в приложении.",
                        "SERVICE_NOT_DECLARED")
                    null -> DiagnosticOutcome.unavailable(
                        "Не удалось проверить регистрацию foreground-службы.",
                        "SERVICE_MANIFEST_UNAVAILABLE")
                }
            },
            probe("queue.foreground", DiagnosticComponent.DOWNLOAD_QUEUE,
                DiagnosticImpact.OPTIONAL) {
                val items = rows()
                LocalDiagnosticRules.foreground(
                    items.count { it.state == DownloadState.RUNNING || it.state == DownloadState.QUEUED },
                    ForegroundServiceObservation.isForegroundObserved())
            },
            probe("storage.selected_folder", DiagnosticComponent.STORAGE,
                DiagnosticImpact.REQUIRED) {
                withContext(Dispatchers.IO) { folder(app) }
            },
            probe("storage.free_space", DiagnosticComponent.STORAGE,
                DiagnosticImpact.OPTIONAL) {
                withContext(Dispatchers.IO) {
                    val bytes = StatFs(app.filesDir.absolutePath).availableBytes
                    LocalDiagnosticRules.freeSpace(bytes)
                }
            },
            probe("editor.lama.sha256", DiagnosticComponent.PHOTO_EDITOR,
                DiagnosticImpact.OPTIONAL, timeoutMillis = 120_000L,
                maxAgeMillis = 60 * 60_000L) {
                withContext(Dispatchers.IO) { verifyLaMa(app) }
            },
            probe("editor.onnx.library", DiagnosticComponent.PHOTO_EDITOR,
                DiagnosticImpact.OPTIONAL) {
                when (classPresent(app, "ai.onnxruntime.OrtEnvironment")) {
                    false -> DiagnosticOutcome(
                        DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
                        "Библиотека ONNX Runtime не найдена в установленной сборке.",
                        "ONNX_LIBRARY_MISSING")
                    true -> DiagnosticOutcome(
                        DiagnosticState.UNAVAILABLE, DiagnosticEvidence.OBSERVED,
                        "ONNX Runtime присутствует, но создание сеанса не выполнялось.",
                        "ONNX_SESSION_UNVERIFIED")
                    null -> DiagnosticOutcome.unavailable(
                        "Не удалось проверить доступность библиотеки ONNX Runtime.",
                        "ONNX_CLASS_UNAVAILABLE")
                }
            },
        )
    }

    private fun classProbe(context: Context, code: String, title: String,
        className: String): DiagnosticProbe =
        probe(code, DiagnosticComponent.MEDIA_RUNTIME, DiagnosticImpact.OPTIONAL) {
            when (classPresent(context, className)) {
                true -> LocalDiagnosticRules.runtimeLibrary(true, title)
                false -> LocalDiagnosticRules.runtimeLibrary(false, title)
                null -> DiagnosticOutcome.unavailable(
                    "Не удалось проверить наличие библиотеки $title.",
                    "RUNTIME_CLASS_UNAVAILABLE")
            }
        }

    /**
     * false means ClassNotFound (verified absence). A linkage failure is not
     * proof of absence, and fatal JVM errors must not be swallowed.
     */
    private fun classPresent(context: Context, name: String): Boolean? = try {
        Class.forName(name, false, context.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        null
    }

    private fun probe(
        code: String,
        component: DiagnosticComponent,
        impact: DiagnosticImpact,
        timeoutMillis: Long = 15_000L,
        maxAgeMillis: Long = 5 * 60_000L,
        block: suspend () -> DiagnosticOutcome,
    ): DiagnosticProbe = object : DiagnosticProbe {
        override val spec = DiagnosticCheckSpec(code, component, impact,
            timeoutMillis = timeoutMillis, maxAgeMillis = maxAgeMillis)
        override suspend fun check(): DiagnosticOutcome = block()
    }

    private fun folder(context: Context): DiagnosticOutcome {
        val settings = StorageSettings(context)
        val saved = settings.treeUri ?: return LocalDiagnosticRules.standardStorage()
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return DiagnosticOutcome(
            DiagnosticState.ERROR, DiagnosticEvidence.VERIFIED,
            "Сохранённый адрес папки недействителен.", "FOLDER_URI_INVALID")
        val grants = context.contentResolver.persistedUriPermissions
        val permission = grants.firstOrNull { it.uri == uri }
        val canRead = permission?.isReadPermission == true
        val canWrite = permission?.isWritePermission == true
        // Never query a SAF provider without an existing persisted grant.
        if (!canRead || !canWrite) return LocalDiagnosticRules.selectedFolder(
            canRead, canWrite, false, false, false, false)
        val state = runCatching {
            val document = DocumentFile.fromTreeUri(context, uri)
            listOf(document?.exists() == true, document?.isDirectory == true,
                document?.canRead() == true, document?.canWrite() == true)
        }.getOrElse {
            // A provider's exception is not proof that the folder was deleted.
            return DiagnosticOutcome.unavailable(
                "Не удалось безопасно прочитать сведения выбранной папки.",
                "FOLDER_PROVIDER_ERROR")
        }
        return LocalDiagnosticRules.selectedFolder(canRead, canWrite,
            exists = state[0], isDirectory = state[1],
            canRead = state[2], canWrite = state[3])
    }

    private suspend fun verifyLaMa(context: Context): DiagnosticOutcome {
        // Exactly the same private path as LaMaModelStore, without installing/initializing.
        val model = File(context.filesDir, "inpainting-models/lama_fp32.onnx")
        if (!model.isFile) return LocalDiagnosticRules.model(false, false, null)
        if (model.length() <= 1_000_000L) return LocalDiagnosticRules.model(true, false, null)
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        return LocalDiagnosticRules.model(true, true,
            actual.equals(LaMaModelStore.SHA256, ignoreCase = true))
    }
}
