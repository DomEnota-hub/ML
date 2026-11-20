package com.domenota.medialoader.data.catalog.visibility

import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.library.PlaylistRepository
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Complete, independently readable history/collection/playlist proof.
 * The archive does not rebind a MediaStore ID, change the history row or
 * remove membership. A changed reference graph blocks automatic completion.
 *
 * The broad playlist snapshot intentionally fails closed after any external
 * playlist edit. We can narrow it per-media after safely verifying a complete
 * association mapping; silently ignoring a changed reference is not allowed.
 */
internal class SelectiveArchiveReferenceGuard(
    private val database:HistoryDatabase,
    private val playlists:PlaylistRepository,
) {
    suspend fun sha256(original:SelectiveArchiveIdentity):String?=withContext(Dispatchers.IO) {
        require(original.wellFormed())
        val row=database.downloadDao().byId(original.catalogId) ?: return@withContext null
        if(row.savedUri!=original.originalUri ||
            row.state!=DownloadState.COMPLETED || row.isPrivate || row.hidden ||
            row.sizeBytes?.let { it!=original.sizeBytes }==true ||
            database.downloadDao().countSavedUriReferences(original.originalUri)!=1
        ) return@withContext null
        // A folder member must retain its original assignment and favorite flag.
        val assignment=database.catalogDao().assignmentById(original.catalogId)
            ?: return@withContext null
        val members=database.organizationDao().identityMembers().sortedBy { it.id }
        // Read the raw persisted playlist snapshot under its own storage mutex;
        // do not trust a previously rendered UI list.
        val playlistDocument=playlists.snapshotHistoricalMedia()
        val digest=MessageDigest.getInstance("SHA-256")
        fun field(value:String?) {
            if(value==null) {digest.update(byteArrayOf(0));return}
            val bytes=value.toByteArray(Charsets.UTF_8)
            digest.update(byteArrayOf(1))
            digest.update(ByteBuffer.allocate(4).putInt(bytes.size).array())
            digest.update(bytes)
        }
        field("ML-SELECTIVE-REFERENCES-V1")
        field(row.toString())
        field(assignment.toString())
        field(playlistDocument)
        field(members.size.toString())
        members.forEach { field(it.toString()) }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
