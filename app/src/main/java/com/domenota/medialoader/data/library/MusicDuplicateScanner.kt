package com.domenota.medialoader.data.library

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import java.io.File
import android.util.AtomicFile
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

internal data class DuplicateScanResult(val groups: List<DuplicateGroup>, val skipped: Int)
internal object MusicDuplicateScanner {
    private data class Stamp(val size: Long, val modified: Long?, val folder: String?)
    private data class Cached(val stamp: Stamp, val track: DuplicateTrack)
    private val cache = object : LinkedHashMap<String,Cached>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String,Cached>?) = size > 64
    }
    /** Caller holds DuplicatePrivacyGuard.lock; scanner writes use the same monitor. */
    fun clearForPrivacy(context: Context) {
        synchronized(cache) { cache.clear() }
        context.noBackupFilesDir.listFiles().orEmpty().filter { it.isDirectory &&
            (it.name == "duplicate-scan-cache-v1" || it.name.matches(Regex("duplicate-scan-cache-v2-a[0-9]+(?:-full)?"))) }.forEach {
            check(it.deleteRecursively()) { "Не удалось очистить кеш дубликатов" }
        }
    }
    private fun stamp(context: Context, uri: Uri): Stamp {
        if (uri.scheme == "file") {
            val file = File(requireNotNull(uri.path)); check(file.isFile && file.length() > 0)
            return Stamp(file.length(), file.lastModified().takeIf { it > 0 }, file.parent)
        }
        check(uri.scheme == "content")
        val values = context.contentResolver.query(uri, arrayOf("_size", "date_modified", "relative_path"), null, null, null)?.use { c ->
            check(c.moveToFirst()); Stamp(c.getLong(0), if (c.isNull(1)) null else c.getLong(1) * 1000, if (c.isNull(2)) null else c.getString(2))
        } ?: error("Метаданные файла недоступны")
        check(values.size > 0); return values
    }
    private suspend fun hash(context: Context, uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val stream = context.contentResolver.openInputStream(uri) ?: error("Файл недоступен")
        stream.use { input -> val buf = ByteArray(128 * 1024); while (true) {
            coroutineContext.ensureActive(); val n = input.read(buf); if (n < 0) break; digest.update(buf,0,n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    /** Hash encoded audio samples, independent of outer metadata tags. NOT a cross-codec acoustic fingerprint. */
    private suspend fun payload(context: Context, uri: Uri): String? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, uri, null)
            val index = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = ex.getTrackFormat(index)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(mime.toByteArray(Charsets.UTF_8))
            for (key in listOf(MediaFormat.KEY_SAMPLE_RATE, MediaFormat.KEY_CHANNEL_COUNT)) {
                if (format.containsKey(key)) digest.update("$key=${format.getInteger(key)}".toByteArray(Charsets.UTF_8))
            }
            for (i in 0..2) format.getByteBuffer("csd-$i")?.duplicate()?.let { digest.update(it) }
            ex.selectTrack(index)
            val size = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(64*1024, 4*1024*1024) else 1024*1024
            val buf = ByteBuffer.allocate(size); var count = 0
            while (true) {
                coroutineContext.ensureActive(); buf.clear(); val n = ex.readSampleData(buf,0); if (n < 0) break
                if (n > buf.capacity()) return null
                buf.position(0); buf.limit(n); digest.update(buf); count++
                if (!ex.advance()) break
            }
            return if (count > 0) digest.digest().joinToString("") { "%02x".format(it) } else null
        } catch (e: CancellationException) { throw e } catch (_: Exception) { return null }
        finally { ex.release() }
    }
    private fun cacheDirectory(context:Context,expanded:Boolean)=File(context.noBackupFilesDir,"duplicate-scan-cache-v2-a${AcousticFingerprint.VERSION}"+if(expanded)"-full" else "")
    private fun checkpoint(context: Context, uri: String,expanded:Boolean): AtomicFile {
        val folder = cacheDirectory(context,expanded).apply { mkdirs() }
        val key = MessageDigest.getInstance("SHA-256").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }
        return AtomicFile(File(folder,key + ".json"))
    }
    private fun loadCheckpoint(context: Context, uri: String, before: Stamp,expanded:Boolean): Cached? = runCatching {
        if (before.modified == null) return null
        val track = MusicDuplicateSessionStore.decodeTrack(JSONObject(checkpoint(context,uri,expanded).openRead().bufferedReader().use { it.readText() }))
        if (track.uri != uri || track.bytes != before.size || track.modified != before.modified || track.folder != before.folder) null
        else Cached(before,track)
    }.getOrNull()
    private fun saveCheckpoint(context: Context, track: DuplicateTrack,expanded:Boolean) { synchronized(DuplicatePrivacyGuard.lock) {
        check(DuplicatePrivacyGuard.accepts(context,track.privacyEpoch,track.id)) { "Поиск устарел после изменения приватности" }
        if (track.modified == null) return
        runCatching {
            val file = checkpoint(context,track.uri,expanded)
            val stream = file.startWrite()
            try { stream.write(MusicDuplicateSessionStore.encodeTrack(track).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (e: Exception) { file.failWrite(stream); throw e }
        }
    } }
    private suspend fun read(context: Context, row: DownloadEntity, linked: Boolean, current: Boolean, epoch: Long, metrics:DuplicateScanMetrics,expanded:Boolean): DuplicateTrack {
        check(DuplicatePrivacyGuard.accepts(context,epoch,row.id)) { "Поиск устарел после изменения приватности" }
        val value = requireNotNull(row.savedUri); val uri = Uri.parse(value); val before = metrics.measure("metadata_stamp"){stamp(context,uri)}
        val cacheKey=value+if(expanded)"#full" else "#sampled"
        val old = metrics.measure("checkpoint_read"){
            synchronized(cache) { cache[cacheKey] } ?: loadCheckpoint(context,value,before,expanded)
                ?: if(expanded)loadCheckpoint(context,value,before,false)?.let{Cached(it.stamp,it.track.copy(acoustic=null))} else null
        }
        if (before.modified != null && old != null && old.stamp == before && old.track.privacyEpoch == epoch && old.track.filename == row.originalName) return old.track.copy(id = row.id, linked = linked, current = current,
            title = row.recognizedTitle ?: row.sourceAudioTitle ?: old.track.title, artist = row.recognizedArtist ?: row.sourceAudioArtist ?: old.track.artist)
        var title = row.recognizedTitle ?: row.sourceAudioTitle
        var artist = row.recognizedArtist ?: row.sourceAudioArtist
        var duration = row.durationMs; var bitrate: Long? = null
        val r = MediaMetadataRetriever()
        metrics.measure("metadata_tags") { try {
            r.setDataSource(context,uri)
            title = title?.takeIf(String::isNotBlank) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            artist = artist?.takeIf(String::isNotBlank) ?: r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: duration
            bitrate = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()
        } catch (e: CancellationException) { throw e } catch (_: Exception) { /* filename fallback */ } finally { r.release() } }
        val sha = metrics.measure("sha256"){hash(context,uri)}; val encoded = metrics.measure("encoded_payload"){payload(context,uri)}
        // The first pass deliberately does not decode audio; exact groups become available first.
        check(stamp(context,uri) == before) { "Файл изменился во время проверки" }
        val track = DuplicateTrack(row.id,title?.takeIf(String::isNotBlank) ?: row.originalName.substringBeforeLast('.'),artist,row.originalName,value,before.size,before.modified,sha,encoded,duration,bitrate,before.folder,linked,current,null,epoch)
        if (before.modified != null) metrics.measure("checkpoint_write") {
            synchronized(DuplicatePrivacyGuard.lock) {
                check(DuplicatePrivacyGuard.accepts(context,epoch,row.id)) { "Поиск устарел после изменения приватности" }
                synchronized(cache) { cache[cacheKey] = Cached(before,track) }
                saveCheckpoint(context,track,expanded)
            }
        }
        return track
    }
    private suspend fun acoustic(context:Context,t:DuplicateTrack,expanded:Boolean,metrics:DuplicateScanMetrics):DuplicateTrack{
        if(t.acoustic!=null)return t
        val uri=Uri.parse(t.uri)
        check(DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id))
        val before=stamp(context,uri)
        check(before.size==t.bytes&&before.modified==t.modified&&before.folder==t.folder)
        val fingerprint=try{metrics.measure("acoustic_decode_fft"){AcousticAudioDecoder.fingerprint(context,uri,t.duration,expanded)}}
            catch(e:CancellationException){throw e}catch(_:Exception){null}
        check(stamp(context,uri)==before)
        val next=t.copy(acoustic=fingerprint)
        if(fingerprint!=null&&before.modified!=null)metrics.measure("checkpoint_write") { synchronized(DuplicatePrivacyGuard.lock){
            check(DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id))
            synchronized(cache){cache[t.uri+if(expanded)"#full" else "#sampled"]=Cached(before,next)}
            saveCheckpoint(context,next,expanded)
        }}
        return next
    }
    suspend fun scan(context: Context, rows: List<DownloadEntity>, linkedIds: Set<String>, currentId: String?,expanded:Boolean=false,
        onPhase:(DuplicateScanPhase)->Unit={},onPartial:(DuplicateScanResult)->Unit={}, progress: (Int,Int) -> Unit): DuplicateScanResult = withContext(Dispatchers.IO) {
        val epoch = DuplicatePrivacyGuard.generation(context)
        val usable = rows.filter { !it.hidden && !it.isPrivate && it.state == DownloadState.COMPLETED && it.mediaType == MediaType.AUDIO && !it.savedUri.isNullOrBlank() }.distinctBy { it.id }
        val publicUris = usable.mapNotNull { it.savedUri }.toSet()
        synchronized(cache) { cache.keys.retainAll(publicUris.flatMap{listOf(it+"#full",it+"#sampled")}.toSet()) }
        File(context.noBackupFilesDir,"duplicate-scan-cache-v1").deleteRecursively()
        val permittedKeys = publicUris.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } + ".json" }.toSet()
        cacheDirectory(context,expanded).listFiles().orEmpty().filter { it.name !in permittedKeys }.forEach { it.delete() }
        val metrics=DuplicateScanMetrics("music")
        val seen = mutableSetOf<String>(); val tracks = mutableListOf<DuplicateTrack>(); var skipped = 0
        // Keep a bounded disk cache; it is never trusted for deletion.
        val files = cacheDirectory(context,expanded).listFiles().orEmpty()
        if (files.size > 6000) files.sortedBy { it.lastModified() }.take(files.size - 4000).forEach { it.delete() }
        onPhase(DuplicateScanPhase.METADATA)
        usable.forEachIndexed { index,row ->
            coroutineContext.ensureActive(); check(DuplicatePrivacyGuard.generation(context) == epoch) { "Поиск прерван изменением приватности" }; progress(index, usable.size)
            try {
                val keys = metrics.measure("identity"){PublicMediaIdentity.capture(context, requireNotNull(row.savedUri))}
                check(DuplicatePrivacyGuard.accepts(context,epoch,row.id,keys)) { "Файл перенесён в Личное" }
                if (keys.any { it in seen }) return@forEachIndexed
                val t = read(context,row,row.id in linkedIds,row.id == currentId,epoch,metrics,expanded)
                seen += keys; tracks += t
            } catch (e: CancellationException) { throw e } catch (_: Exception) { skipped++ }
        }
        val active = coroutineContext
        fun checkActive(){active.ensureActive();check(DuplicatePrivacyGuard.generation(context)==epoch){"Поиск устарел"}}
        val partial=MusicDuplicatePolicy.groups(tracks.map{it.copy(acoustic=null)},::checkActive)
        onPartial(DuplicateScanResult(partial,skipped));onPhase(DuplicateScanPhase.ACOUSTIC)
        for(index in tracks.indices){
            checkActive();progress(index,tracks.size)
            try{tracks[index]=acoustic(context,tracks[index],expanded,metrics)}catch(e:CancellationException){throw e}catch(_:Exception){skipped++}
        }
        progress(tracks.size,tracks.size);onPhase(DuplicateScanPhase.GROUPING)
        val groups=metrics.measure("grouping"){MusicDuplicatePolicy.groups(tracks) { active.ensureActive(); check(DuplicatePrivacyGuard.generation(context) == epoch) { "Поиск устарел" } }}
        metrics.report()
        DuplicateScanResult(groups,skipped)
    }
    /** Restore only still-identical public rows; no audio decoding or hashing here. */
    suspend fun restore(context: Context, saved: DuplicateScanResult, rows: List<DownloadEntity>,
        linkedIds: Set<String>, currentId: String?): DuplicateScanResult = withContext(Dispatchers.IO) {
        val byId = rows.associateBy { it.id }
        var stale = 0
        val groups = saved.groups.mapNotNull { group ->
            val valid = group.tracks.mapNotNull { t ->
                coroutineContext.ensureActive()
                val row = byId[t.id]
                val ok = DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id) && row != null && !row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED &&
                    row.mediaType == MediaType.AUDIO && row.savedUri == t.uri && row.originalName == t.filename &&
                    t.modified != null && runCatching { stamp(context, Uri.parse(t.uri)).let { it.size == t.bytes && it.modified == t.modified } }.getOrDefault(false)
                if (ok) t.copy(linked = t.id in linkedIds, current = t.id == currentId)
                else { stale++; null }
            }
            if (valid.size >= 2) group.copy(tracks = valid) else null
        }
        DuplicateScanResult(groups, saved.skipped + stale)
    }
    suspend fun verify(context: Context, groups: List<DuplicateGroup>, selected: Set<String>, currentRows: List<DownloadEntity>): Unit = withContext(Dispatchers.IO) {
        check(MusicDuplicatePolicy.validSelection(groups,selected)) { "Недопустимое выделение" }
        val rows = currentRows.associateBy { it.id }
        for (group in groups.filter { it.tracks.any { t -> t.id in selected } }) {
            // Verify every selected file and every retained file. Never use the cache for deletion.
            for (t in group.tracks) {
                coroutineContext.ensureActive()
                check(DuplicatePrivacyGuard.accepts(context,t.privacyEpoch,t.id)) { "Поиск устарел после изменения приватности" }
                val row = rows[t.id] ?: error("Файл исчез: ${t.filename}")
                check(!row.hidden && !row.isPrivate && row.state == DownloadState.COMPLETED && row.mediaType == MediaType.AUDIO && row.savedUri == t.uri) { "Запись изменилась: ${t.filename}" }
                val uri = Uri.parse(t.uri); val st = stamp(context,uri)
                check(st.size == t.bytes && st.modified == t.modified && hash(context,uri) == t.sha && stamp(context,uri) == st) { "Файл изменился: ${t.filename}" }
            }
        }
    }
}
