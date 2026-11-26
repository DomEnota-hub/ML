package com.domenota.medialoader.data.playback

import android.content.Context
import android.util.AtomicFile
import androidx.media3.common.MediaItem
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal data class AudioBookmark(val id: String, val name: String, val positionMs: Long)

internal object AudioBookmarkIdentity {
    fun resolve(media: MediaItem, aliases: Map<String, LibraryMediaRef>): LibraryMediaRef? {
        if (PrivatePlaybackPolicy.isPrivate(media)) return null
        val extra = media.mediaMetadata.extras
        val uri = extra?.getString(AudioPlaybackMetadata.PUBLIC_URI) ?: media.localConfiguration?.uri?.toString()
        if (uri.isNullOrBlank()) return null
        val captured = extra?.getStringArrayList(PublicMediaIdentity.EXTRA_KEYS).orEmpty()
        return (listOf(media.mediaId) + captured).mapNotNull(aliases::get)
            .filter { it.item.savedUri == uri && eligible(it) }.distinctBy { it.key }.singleOrNull()
    }

    private fun eligible(ref: LibraryMediaRef): Boolean = !ref.item.isPrivate && !ref.item.hidden &&
        ref.item.mediaType == MediaType.AUDIO && ref.item.state == DownloadState.COMPLETED &&
        !ref.item.savedUri.isNullOrBlank()

    /** Stable app ID, or physical identity/fingerprinted phone locator; never the displayed title.
     * Hash addresses so the bookmark document contains no filenames or URI strings. */
    fun keys(ref: LibraryMediaRef): Set<String> {
        if (!eligible(ref)) return emptySet()
        val raw = ListeningHistoryPolicy.keys(ref.item, ref.aliases).filterNot { it.startsWith("app:") } +
            listOfNotNull(ref.appDownloadId?.takeIf { it.isNotBlank() }?.let { "app:$it" })
        return raw.mapTo(linkedSetOf()) { key ->
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }
    }

    fun position(positionMs: Long, durationMs: Long): Long? =
        positionMs.takeIf { durationMs > 0 && it >= 0 && it < durationMs }
}

internal data class AudioBookmarkAdmission(val ref: LibraryMediaRef, val privacyEpoch: Long) {
    val keys = AudioBookmarkIdentity.keys(ref)
    fun requireCurrent(context: Context) {
        HistoricalRecoverySignals.requireIdle()
        check(keys.isNotEmpty() && DuplicatePrivacyGuard.accepts(context, privacyEpoch,
            ref.appDownloadId ?: ref.key, ref.aliases + ref.key)) { "Трек недоступен" }
    }
}

/** A small atomic app-private document, excluded from Android backup. No audio is changed.
 * Only a currently authorized public track can address its records; there is no global listing. */
internal class AudioBookmarkStore(context: Context, name: String = "audio-bookmarks-v1.json") {
    private val context = context.applicationContext
    private val file = AtomicFile(File(this.context.noBackupFilesDir, name))
    private data class Record(val value: AudioBookmark, val keys: Set<String>)
    private fun records(): List<Record> {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return emptyList()
        val json = JSONObject(String(file.readFully(), Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val entries = json.getJSONArray("bookmarks")
        return (0 until entries.length()).map { index ->
            val r = entries.getJSONObject(index)
            val keys = r.getJSONArray("keys")
            val identities = (0 until keys.length()).map { keys.getString(it) }.toSet()
            val value = AudioBookmark(r.getString("id"), r.getString("name"), r.getLong("position"))
            require(value.id.isNotBlank() && value.name.isNotBlank() && value.positionMs >= 0 &&
                identities.isNotEmpty() && identities.all { it.matches(Regex("[0-9a-f]{64}")) })
            Record(value, identities)
        }.also { require(it.map { row -> row.value.id }.distinct().size == it.size) }
    }
    private fun write(records: List<Record>) {
        val rows = JSONArray()
        records.forEach { r -> rows.put(JSONObject().put("id", r.value.id).put("name", r.value.name)
            .put("position", r.value.positionMs).put("keys", JSONArray(r.keys.sorted()))) }
        val out = file.startWrite()
        try {
            out.write(JSONObject().put("version", 1).put("bookmarks", rows).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(out)
        } catch (failure: Throwable) {
            file.failWrite(out)
            throw failure
        }
    }
    private fun visible(records: List<Record>, keys: Set<String>): List<AudioBookmark> =
        records.filter { row -> row.keys.any(keys::contains) }.map { it.value }
            .sortedWith(compareBy<AudioBookmark> { it.positionMs }.thenBy { it.id })

    suspend fun list(access: AudioBookmarkAdmission): List<AudioBookmark> = withContext(Dispatchers.IO) {
        synchronized(DuplicatePrivacyGuard.lock) {
            access.requireCurrent(context)
            visible(records(), access.keys)
        }
    }
    suspend fun add(access: AudioBookmarkAdmission, name: String, position: Long, duration: Long): List<AudioBookmark> =
        withContext(Dispatchers.IO) {
            synchronized(DuplicatePrivacyGuard.lock) {
                access.requireCurrent(context)
                val label = name.trim()
                require(label.isNotEmpty() && label.length <= 100)
                require(AudioBookmarkIdentity.position(position, duration) != null)
                val current = records()
                val next = current + Record(AudioBookmark(UUID.randomUUID().toString(), label, position), access.keys)
                write(next)
                visible(next, access.keys)
            }
        }
    suspend fun delete(access: AudioBookmarkAdmission, id: String): List<AudioBookmark> =
        withContext(Dispatchers.IO) {
            synchronized(DuplicatePrivacyGuard.lock) {
                access.requireCurrent(context)
                val current = records()
                val next = current.filterNot { it.value.id == id && it.keys.any(access.keys::contains) }
                if (next != current) write(next)
                visible(next, access.keys)
            }
        }
}
