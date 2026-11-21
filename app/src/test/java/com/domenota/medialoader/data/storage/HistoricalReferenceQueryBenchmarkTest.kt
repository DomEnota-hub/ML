package com.domenota.medialoader.data.storage

import androidx.room.Room
import com.domenota.medialoader.core.logging.AppLog
import androidx.room.withTransaction
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.ceil

/** Controlled QUERY-LAYER experiment, never a physical HIDE/SHOW benchmark.
 * 512 synthetic fat rows, identical negative checks, 1/4/20 sources x seven calls.
 * Three fresh Room-connection and three reused-connection samples per workload.
 * Fresh connection is NOT a cold OS/JVM/URI cache. Setup and close are untimed.
 * No files/provider media are created; numbers cannot be compared to Xiaomi totals.
 * Output is machine-readable ML203_QUERY_BENCH_SAMPLE / ML203_QUERY_BENCH_SUMMARY.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalReferenceQueryBenchmarkTest {
    // Exercise every workload/connection/logging combination in normal CI.
    // Only repeated measurements of identical read-only queries are opt-in.
    private val performanceRun = System.getProperty("ml.perfBenchmarks") == "true"
    private val rowCount = 512
    private data class Sample(val millis: Double, val gcCount: Long?, val gcMillis: Long?)
    private data class Gc(val count: Long?, val millis: Long?)

    private suspend fun database(): HistoryDatabase {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(),
            HistoryDatabase::class.java).build()
        try {
            val fat = "synthetic metadata ".repeat(16)
            db.withTransaction {
                repeat(rowCount) { index ->
                    // Limited locator cardinality avoids URI-cache thrash obscuring
                    // the difference in cursor projection and entity allocation.
                    val uri = "content://media/external/video/media/${1000 + index % 32}"
                    db.downloadDao().upsert(DownloadEntity("history-$index", "synthetic", "$fat.mp4",
                        MediaType.VIDEO, DownloadState.COMPLETED, uri, 256L, index.toLong(),
                        privateSourceUri = uri, previewUrl = uri, sourceAudioArtworkUrl = uri,
                        recognitionArtworkUrl = uri, sourceUrl = uri, sourcePageUrl = uri,
                        errorMessage = fat, sourceAudioArtist = fat, sourceAudioTitle = fat,
                        recognizedArtist = fat, recognizedTitle = fat, recognizedAlbum = fat,
                        recognitionTrackId = fat, recognitionSource = fat, formatSelector = fat,
                        hidden = index % 3 == 0, isPrivate = index % 5 == 0))
                }
            }
            return db
        } catch (failure: Throwable) { db.close(); throw failure }
    }

    // Best effort: Robolectric runs on a JVM; unsupported runtimes emit null.
    private fun gc(): Gc = try {
        val factory = Class.forName("java.lang.management.ManagementFactory")
        val beanType = Class.forName("java.lang.management.GarbageCollectorMXBean")
        val beans = factory.getMethod("getGarbageCollectorMXBeans").invoke(null) as List<*>
        fun sum(method: String): Long? {
            val values = beans.map { (beanType.getMethod(method).invoke(it) as Number).toLong() }
            return if (values.any { it < 0 }) null else values.sum()
        }
        Gc(sum("getCollectionCount"), sum("getCollectionTime"))
    } catch (_: Exception) { Gc(null, null) }

    private suspend fun measure(block: suspend () -> Unit): Sample {
        if (!performanceRun) {
            block()
            return Sample(0.0, null, null) // no timing or GC sampling in regression mode
        }
        val before = gc()
        val start = System.nanoTime()
        block()
        val elapsed = System.nanoTime() - start
        val after = gc()
        return Sample(elapsed / 1_000_000.0,
            before.count?.let { first -> after.count?.minus(first) },
            before.millis?.let { first -> after.millis?.minus(first) })
    }

    private suspend fun check(db: HistoryDatabase, kind: String, optimized: Boolean,
        targets: List<Pair<String, Set<String>>>) {
        var references = 0
        for ((uri, aliases) in targets) repeat(if (performanceRun) 7 else 1) {
            val found = if (kind == "artwork") {
                if (optimized) HistoricalReferenceQueries.hasArtwork(db, uri)
                else !db.downloadDao().all().none {
                    listOf(it.previewUrl, it.sourceAudioArtworkUrl, it.recognitionArtworkUrl).contains(uri)
                }
            } else {
                if (optimized) HistoricalReferenceQueries.hasLiveSource(db, aliases)
                else !db.downloadDao().all().none { row ->
                    listOfNotNull(row.savedUri, row.privateSourceUri, row.previewUrl,
                        row.sourceAudioArtworkUrl, row.recognitionArtworkUrl, row.sourceUrl,
                        row.sourcePageUrl).any { value ->
                        value in aliases || PublicMediaIdentity.uriKeys(value).any(aliases::contains)
                    }
                }
            }
            if (found) references++
        }
        // Correctness only; no arbitrary speed or allocation thresholds.
        assertEquals(0, references)
    }

    private fun emitSummary(kind: String, files: Int, mode: String, path: String, samples: List<Sample>) {
        val times = samples.map { it.millis }.sorted()
        fun percentile(fraction: Double) = times[(ceil(times.size * fraction).toInt() - 1).coerceAtLeast(0)]
        println("ML203_QUERY_BENCH_SUMMARY " + JSONObject()
            .put("scope", "query_layer_only_not_physical_hide_show")
            .put("kind", kind).put("sources", files).put("verifyCallsPerSource", 7)
            .put("connectionMode", mode).put("path", path).put("samples", times.size)
            .put("p50Ms", percentile(.50)).put("p90Ms", percentile(.90)).put("p95Ms", percentile(.95)))
    }

    @Test fun pairedWallTimeForOriginalAllAndFreshMinimalQueries() = runBlocking {
        val originalDiagnostic=AppLog.isDetailedPerformance()
        AppLog.setDetailedPerformance(false)
        try {
        for (kind in listOf("artwork", "live_source")) for (files in listOf(1, 4, 20)) {
            val targets = (0 until files).map { index ->
                val uri = "content://media/external/video/media/${900000 + index}"
                uri to (PublicMediaIdentity.uriKeys(uri) + uri)
            }
            for (mode in listOf("fresh_connection_warm_process", "reused_connection")) {
                val samples = mutableMapOf(false to mutableListOf<Sample>(), true to mutableListOf<Sample>())
                val reused = if (mode == "reused_connection") database() else null
                try {
                    if (reused != null) {
                        // One untimed check primes both paths equally.
                        check(reused, kind, false, targets.take(1))
                        check(reused, kind, true, targets.take(1))
                    }
                    repeat(if (performanceRun) 3 else 1) { round ->
                        val db = reused ?: database()
                        try {
                            val actualRows = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM downloads")
                                .use { it.moveToFirst(); it.getInt(0) }
                            val actualColumns = db.openHelper.readableDatabase.query("PRAGMA table_info(downloads)")
                                .use { it.count }
                            assertEquals(rowCount, actualRows)
                            // Alternate which path is measured first to limit ordering bias.
                            for (optimized in if (round % 2 == 0) listOf(false, true) else listOf(true, false)) {
                                val sample = measure { check(db, kind, optimized, targets) }
                                samples.getValue(optimized).add(sample)
                                if (performanceRun) println("ML203_QUERY_BENCH_SAMPLE " + JSONObject()
                                    .put("kind", kind).put("sources", files).put("verifyCallsPerSource", 7)
                                    .put("connectionMode", mode).put("round", round)
                                    .put("path", if (optimized) "minimal_cursor" else "original_all_none")
                                    .put("elapsedMs", sample.millis)
                                    .put("jvmGcCountDelta", sample.gcCount ?: JSONObject.NULL)
                                    .put("jvmGcTimeMsDelta", sample.gcMillis ?: JSONObject.NULL)
                                    .put("databaseRows", actualRows).put("schemaColumns", actualColumns)
                                    .put("resultColumnsPerQuery", if (!optimized) actualColumns else if (kind == "artwork") 1 else 7)
                                    .put("resultRowsPerNegativeQuery", if (optimized && kind == "artwork") 0 else actualRows)
                                    .put("entityRowsPerQuery", if (optimized) 0 else actualRows))
                            }
                        } finally { if (reused == null) db.close() }
                    }
                    if (performanceRun) for (optimized in listOf(false, true)) emitSummary(kind, files, mode,
                        if (optimized) "minimal_cursor" else "original_all_none", samples.getValue(optimized))
                } finally { reused?.close() }
            }
        }
        } finally { AppLog.setDetailedPerformance(originalDiagnostic) }
    }

    @Test fun diagnosticCallOverheadForFourSourcesUsesTheSameFreshChecks() = runBlocking {
        AppLog.init(RuntimeEnvironment.getApplication())
        val previous=AppLog.isDetailedPerformance()
        val db=database()
        val targets=(0 until 4).map { index ->
            val uri="content://media/external/video/media/${950000+index}"
            uri to (PublicMediaIdentity.uriKeys(uri)+uri)
        }
        try {
            for(kind in listOf("artwork","live_source")) {
                // Prime both flags before alternating their measured order.
                for(enabled in listOf(false,true)) {
                    AppLog.setDetailedPerformance(enabled)
                    check(db,kind,true,targets.take(1))
                }
                val samples=mapOf(false to mutableListOf<Sample>(),true to mutableListOf<Sample>())
                repeat(if (performanceRun) 6 else 1) { round ->
                    for(enabled in if(round%2==0) listOf(false,true) else listOf(true,false)) {
                        AppLog.setDetailedPerformance(enabled)
                        samples.getValue(enabled).add(measure { check(db,kind,true,targets) })
                    }
                }
                if (performanceRun) for(enabled in listOf(false,true)) println("ML203_QUERY_DIAGNOSTICS "+JSONObject()
                    .put("scope","query_layer_real_logger_enqueue_not_phone_fs_flush")
                    .put("appLogInitialized",true).put("alternatingOrder",true)
                    .put("enabled",enabled).put("kind",kind).put("sources",4)
                    .put("samplesMs",JSONArray(samples.getValue(enabled).map { it.millis })))
            }
        } finally { AppLog.setDetailedPerformance(previous);db.close() }
    }

}
