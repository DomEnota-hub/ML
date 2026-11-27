package com.domenota.medialoader.data.playback

import com.domenota.medialoader.data.tasks.*
import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.ui.components.preparePersistedAudioWaveform
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Silent, persistent WorkManager analysis. A batch has no foreground notification and
 * never modifies music or adds it to visible download history.
 * Unique work avoids overlapping scans; a persisted cursor continues across process death.
 */
class AudioWaveformPrecomputeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private companion object { val sweepMutex = Mutex() }
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val requested = inputData.getString("requestedUri")
        if (requested != null) {
            // Priority single-track request, independent of the backlog of public music.
            // No list, notification, or UI state is exposed.
            return@withContext runCatching {
                val repo=MediaRepository.get(app)
                val admission=WaveformAdmission.read(app,repo)
                if(!admission.allows(app,requested,null)||!admission.current(app,repo))return@runCatching Result.failure()
                val uri = Uri.parse(requested)
                val duration = inputData.getLong("durationMs", 0L).takeIf{it>0L} ?: readDurationMs(app,uri)
                if(duration<=0L)return@runCatching Result.failure()
                val outcome=preparePersistedAudioWaveform(app,uri,duration){isStopped||!admission.currentFast(app,repo,requested)}
                if(!admission.current(app,repo)||outcome.outcome==WaveformOutcome.FAILED)Result.failure() else Result.success()
            }.getOrElse {
                if (it is java.util.concurrent.CancellationException && isStopped) throw it
                Result.retry()
            }
        }
        if(inputData.getBoolean("periodicSweep",false)) {
            AudioWaveformPrecompute.request(app,periodic=true)
            return@withContext Result.success()
        }
        sweepMutex.withLock { processSweep(app) }
    }

    private suspend fun processSweep(app:Context):Result {
        val tracks = linkedMapOf<String, Long>()
        val repo=MediaRepository.get(app)
        val admission=try { WaveformAdmission.read(app,repo) } catch(e:java.util.concurrent.CancellationException){throw e} catch(_:Exception){return Result.retry()}
        // Never index the application's hidden or private audio history.
        try {
            admission.rows.forEach { row ->
                if (row.state == DownloadState.COMPLETED && row.mediaType == MediaType.AUDIO &&
                    !row.hidden && !row.isPrivate && !row.savedUri.isNullOrBlank()
                ) if(admission.allows(app,row.savedUri!!,row.id))tracks[row.savedUri!!] = row.durationMs ?: 0L
            }
        } catch(e:java.util.concurrent.CancellationException){throw e} catch(_:Exception){return Result.retry()}
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED) {
            try {
                app.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION, MediaStore.MediaColumns.DATA),
                    null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    val pathCol=cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val duration = if (durationCol >= 0 && !cursor.isNull(durationCol))
                            cursor.getLong(durationCol) else 0L
                        if(isStopped)throw java.util.concurrent.CancellationException("Stopped")
                        val physical=if(admission.requiresPhysicalProof && pathCol>=0&&!cursor.isNull(pathCol))PublicMediaIdentity.pathKeys(cursor.getString(pathCol)) else emptySet()
                        if(admission.allows(app,uri.toString(),"phone:audio:$id",physical)){
                            if(duration>0L)tracks[uri.toString()]=duration else tracks.putIfAbsent(uri.toString(),0L)
                        }
                    }
                } ?: return Result.retry()
            } catch(e:java.util.concurrent.CancellationException){throw e} catch(_:Exception){return Result.retry()}
        }
        if(!admission.current(app,repo))return Result.retry()
        val entries = tracks.entries.sortedBy { it.key }
        val prefs = app.getSharedPreferences("waveform_precompute_v1", Context.MODE_PRIVATE)
        val store=WaveformSweepStore(prefs)
        val inventory=WaveformSweep.fingerprint(entries.map { it.key })
        val restored=store.read()
        val continuation=inputData.getString("continuationToken")
        if(continuation!=null && continuation!=restored?.token?.value)return Result.success()
        if(restored!=null && (restored.inventory!=inventory || restored.total!=entries.size)) {
            val abandoned=TaskReporter.create(TaskOwner.WAVEFORM,TaskKind.WAVEFORM,restored.token.value)
            abandoned.report(TaskStatus.CANCELLED,files=countProgress(restored.cursor,restored.total),errors=restored.outcomes.failed,details=restored.details())
        }
        var sweep=restored?.takeIf { it.inventory==inventory && it.total==entries.size }
            ?: WaveformSweep(TaskCorrelation.create(),inventory,entries.size,System.currentTimeMillis(),
                if(inputData.getBoolean("periodicOrigin",false)) TaskTrigger.PERIODIC else TaskTrigger.BACKGROUND)
        val report=TaskReporter.create(TaskOwner.WAVEFORM,TaskKind.WAVEFORM,sweep.token.value)
        fun publish(status:TaskStatus=TaskStatus.RUNNING,wait:TaskWait?=null,stage:TaskStage=TaskStage.SCANNING) {
            report.report(status,files=countProgress(sweep.cursor,sweep.total),errors=sweep.outcomes.failed,
                wait=wait,stage=stage,details=sweep.details(recovered=restored!=null))
        }
        if(!store.save(sweep)) { publish(TaskStatus.WAITING,TaskWait.STORAGE);return Result.retry() }
        if(sweep.finished) {
            publish(if(sweep.outcomes.failed>0||sweep.outcomes.skipped>0)TaskStatus.PARTIAL else TaskStatus.COMPLETE)
            return if(store.clear())Result.success() else Result.retry()
        }
        publish()
        val stop=(sweep.cursor+200).coerceAtMost(entries.size)
        val batchStart=android.os.SystemClock.elapsedRealtime()
        try {
            for(index in sweep.cursor until stop) {
                if(isStopped) throw java.util.concurrent.CancellationException("Stopped")
                val wait=TaskHistoryStore.waitReason(app)
                if(wait!=null) {
                    if(!store.save(sweep)) { publish(TaskStatus.WAITING,TaskWait.STORAGE);return Result.retry() }
                    publish(TaskStatus.WAITING,wait);return Result.retry()
                }
                if(!admission.current(app,repo)){publish(TaskStatus.WAITING,TaskWait.RESOURCE);return Result.retry()}
                val (source, knownDuration)=entries[index]
                val result=try {
                    val uri=Uri.parse(source)
                    val duration=knownDuration.takeIf{it>0L} ?: readDurationMs(app,uri)
                    if(duration<=0L) WaveformPreparation(emptyList(),WaveformOutcome.SKIPPED)
                    else {
                        val waiting=BackgroundMediaWork.isBusy()
                        if(waiting)publish(TaskStatus.WAITING,TaskWait.RESOURCE)
                        BackgroundMediaWork.awaitIdle()
                        if(waiting)publish()
                        if(!admission.current(app,repo))throw java.util.concurrent.CancellationException("Visibility changed")
                        preparePersistedAudioWaveform(app,uri,duration){isStopped||!admission.currentFast(app,repo,source)}
                    }
                } catch(e:java.util.concurrent.CancellationException) { throw e }
                  catch(_:Exception) { WaveformPreparation(emptyList(),WaveformOutcome.FAILED,TaskFailure.IO) }
                if(!admission.current(app,repo)){publish(TaskStatus.WAITING,TaskWait.RESOURCE);return Result.retry()}
                sweep=sweep.advance(result.outcome,result.failure)
                publish()
                if(sweep.cursor%20==0){
                    if(!store.save(sweep)){publish(TaskStatus.WAITING,TaskWait.STORAGE);return Result.retry()}
                    if(android.os.SystemClock.elapsedRealtime()-batchStart>=90_000L)break
                }
            }
            if(!store.save(sweep)) { publish(TaskStatus.WAITING,TaskWait.STORAGE);return Result.retry() }
            if(!sweep.finished) {
                publish(TaskStatus.QUEUED,stage=TaskStage.ENQUEUE)
                AudioWaveformPrecompute.continueRemaining(app,sweep.token.value)
            } else {
                publish(if(sweep.outcomes.failed>0||sweep.outcomes.skipped>0)TaskStatus.PARTIAL else TaskStatus.COMPLETE)
                if(!store.clear())return Result.retry()
            }
            return Result.success()
        } catch(e:java.util.concurrent.CancellationException) {
            store.save(sweep)
            // A privacy generation change is a retry, not a cancelled WorkManager chain.
            publish(TaskStatus.WAITING,TaskWait.RESOURCE)
            if(isStopped)throw e
            return Result.retry()
        } catch(_:Exception) {
            store.save(sweep)
            publish(TaskStatus.WAITING,TaskWait.RESOURCE)
            return Result.retry()
        }
    }

    private fun readDurationMs(context: Context, uri: Uri): Long = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }.getOrDefault(0L)
}

object AudioWaveformPrecompute {
    private const val ONCE = "waveform-background-once-v3"
    private const val PERIODIC = "waveform-background-periodic-v3"

    /** Starts without waiting for device idle; OS may delay on battery restrictions. */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val prefs = context.getSharedPreferences("waveform_precompute_v1", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("background_policy_v3", false)) {
            manager.cancelUniqueWork("waveform-once-v1")
            manager.cancelUniqueWork("waveform-periodic-v1")
            manager.cancelUniqueWork("waveform-idle-once-v2")
            manager.cancelUniqueWork("waveform-idle-periodic-v2")
            prefs.edit().putBoolean("background_policy_v3", true).apply()
        }
        val constraints = WaveformWorkPolicy.bulkConstraints()
        manager.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AudioWaveformPrecomputeWorker>(6, TimeUnit.HOURS)
                .setInputData(workDataOf("periodicSweep" to true))
                .setConstraints(constraints).build()
        )
        request(context)
    }

    /** Queue the next 20 tracks after a successful checkpoint, with no retry back-off. */
    internal fun continueRemaining(context: Context,token:String) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<AudioWaveformPrecomputeWorker>()
                .setInputData(workDataOf("continuationToken" to token))
                .setConstraints(WaveformWorkPolicy.bulkConstraints())
                .build()
        )
    }

    /** Priority cache miss from the editor is queued separately from the library sweep. */
    fun requestImmediate(context: Context, uri: Uri, durationMs: Long) {
        val id = MessageDigest.getInstance("SHA-256")
            .digest(uri.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "waveform-priority-$id", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AudioWaveformPrecomputeWorker>()
                .setConstraints(WaveformWorkPolicy.priorityConstraints())
                .setInputData(workDataOf("requestedUri" to uri.toString(), "durationMs" to durationMs))
                .build()
        )
    }

    /** A new local track or MediaStore audio update triggers a silent incremental pass. */
    fun request(context: Context) = request(context,periodic=false)
    internal fun request(context:Context,periodic:Boolean) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AudioWaveformPrecomputeWorker>()
                .setInputData(workDataOf("periodicOrigin" to periodic))
                .setConstraints(WaveformWorkPolicy.bulkConstraints())
                .build()
        )
    }
}
