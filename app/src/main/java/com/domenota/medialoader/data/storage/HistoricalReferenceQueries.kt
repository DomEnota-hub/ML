package com.domenota.medialoader.data.storage

import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.playback.PublicMediaIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Fresh minimal cursor on EVERY safety boundary; never caches a deletion decision.
 * Avoid mapping all download columns into DownloadEntity just to inspect locators. */
internal object HistoricalReferenceQueries {
    suspend fun hasActiveDownloads(db: HistoryDatabase): Boolean = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("SELECT 1 FROM downloads WHERE state NOT IN ('COMPLETED','FAILED','CANCELLED') OR state IS NULL LIMIT 1").use { it.moveToFirst() }
    }

    suspend fun hasArtwork(db: HistoryDatabase, uri: String): Boolean = withContext(Dispatchers.IO) { measured("ARTWORK") { db.openHelper.readableDatabase.query(
        "SELECT 1 FROM downloads WHERE previewUrl=? OR sourceAudioArtworkUrl=? OR recognitionArtworkUrl=? LIMIT 1",
        arrayOf(uri,uri,uri)).use { it.moveToFirst() } } }

    suspend fun hasLiveSource(db: HistoryDatabase, aliases: Set<String>, exceptId: String? = null,
        physicalOnly: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val columns = if(physicalOnly) "savedUri,privateSourceUri" else
            "savedUri,privateSourceUri,previewUrl,sourceAudioArtworkUrl,recognitionArtworkUrl,sourceUrl,sourcePageUrl"
        val where = if(exceptId==null) "" else " WHERE id<>?"
        val args = if(exceptId==null) emptyArray<Any>() else arrayOf<Any>(exceptId)
        measured("LIVE_SOURCE") { db.openHelper.readableDatabase.query("SELECT $columns FROM downloads$where",args).use { cursor ->
            while(cursor.moveToNext()) for(column in 0 until cursor.columnCount) {
                if(cursor.isNull(column)) continue
                val value=cursor.getString(column)
                if(value in aliases || PublicMediaIdentity.uriKeys(value).any(aliases::contains)) return@use true
            }
            false
        } }
    }
    private inline fun <T> measured(stage: String, block: () -> T): T {
        if(!AppLog.isDetailedPerformance()) return block()
        val start=System.nanoTime()
        var success=false
        try { return block().also { success=true } }
        finally { AppLog.i("FolderVisibility","REFERENCE_READ stage=$stage elapsedUs=${(System.nanoTime()-start)/1000} success=$success") }
    }
}

/** Caches parsing only for EXACT current bytes, bounded to one modest document.
 * The caller still reads the live journal/preferences first. No state/permission proof is cached.
 * Values must be private read-only parse trees; mutation paths must parse independently. */
internal class ExactTextReadCache<T>(private val maxChars: Int = 131_072) {
    private var text: String? = null
    private var value: T? = null
    @Synchronized fun read(raw: String, parse: (String) -> T): T {
        if(raw==text) { @Suppress("UNCHECKED_CAST") return value as T }
        val parsed=parse(raw)
        if(raw.length<=maxChars) { text=raw; value=parsed } else clear()
        return parsed
    }
    @Synchronized fun clear() { text=null;value=null }
}
