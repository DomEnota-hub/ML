package com.domenota.medialoader.data.playback

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.ui.components.hasCachedAudioWaveform
import com.domenota.medialoader.ui.components.loadAudioWaveform
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Silent, persistent WorkManager analysis. A batch has no foreground notification and
 * never modifies music or adds it to visible download history.
 * Unique work avoids overlapping scans; a persisted cursor continues across process death.
 */
class AudioWaveformPrecomputeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext
        val tracks = linkedMapOf<String, Long>()
        // Never index the application's hidden or private audio history.
        runCatching {
            MediaRepository.get(app).downloads.first().forEach { row ->
                if (row.state == DownloadState.COMPLETED && row.mediaType == MediaType.AUDIO &&
                    !row.hidden && !row.isPrivate && !row.savedUri.isNullOrBlank()
                ) tracks[row.savedUri!!] = row.durationMs ?: 0L
            }
        }
        val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
            else Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(app, permission) == PackageManager.PERMISSION_GRANTED) {
            runCatching {
                app.contentResolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DURATION),
                    null, null, MediaStore.Audio.Media.DATE_ADDED + " DESC"
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                    val durationCol = cursor.getColumnIndex(MediaStore.Audio.Media.DURATION)
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idCol)
                        val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                        val duration = if (durationCol >= 0 && !cursor.isNull(durationCol))
                            cursor.getLong(durationCol) else 0L
                        tracks.putIfAbsent(uri.toString(), duration)
                    }
                }
            }
        }
        val entries = tracks.entries.toList()
        val prefs = app.getSharedPreferences("waveform_precompute_v1", Context.MODE_PRIVATE)
        val inventory = entries.fold(1) { hash, entry ->
            31 * hash + entry.key.hashCode()
        }
        val start = if (prefs.getInt("inventory", 0) == inventory)
            prefs.getInt("cursor", 0).coerceIn(0, entries.size) else 0
        // WorkManager runs are time-limited: make progress even for large music libraries.
        val stop = (start + 20).coerceAtMost(entries.size)
        for (index in start until stop) {
            if (isStopped) return@withContext Result.retry()
            val (source, knownDuration) = entries[index]
            runCatching {
                val uri = Uri.parse(source)
                val duration = knownDuration.takeIf { it > 0L } ?: readDurationMs(app, uri)
                if (duration > 0L && !hasCachedAudioWaveform(app, uri, duration)) {
                    loadAudioWaveform(app, uri, duration)
                }
            }
        }
        prefs.edit().putInt("inventory", inventory).putInt("cursor", if (stop < entries.size) stop else 0).apply()
        if (stop < entries.size) Result.retry() else Result.success()
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
    private const val ONCE = "waveform-once-v1"
    private const val PERIODIC = "waveform-periodic-v1"

    /** Scheduled even if the audio editor is never opened. Android may defer on low battery. */
    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
        manager.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AudioWaveformPrecomputeWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints).build()
        )
        request(context)
    }

    /** A new local track or MediaStore audio update triggers a silent incremental pass. */
    fun request(context: Context) {
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            ONCE, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<AudioWaveformPrecomputeWorker>()
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
        )
    }
}
