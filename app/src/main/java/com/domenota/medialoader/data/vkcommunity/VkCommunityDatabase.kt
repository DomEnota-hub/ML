package com.domenota.medialoader.data.vkcommunity

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.UUID

enum class VkJobPhase { SCANNING, READY, DOWNLOADING, FINISHED, ERROR, CANCELLED }
enum class VkItemPhase { FOUND, DOWNLOADING, DONE, DUPLICATE, ERROR }

data class VkCommunityJob(
    val id: String,
    val link: String,
    val startDate: String,
    val endDate: String,
    val zoneId: String,
    val phase: VkJobPhase,
    val offset: Int,
    val scannedPosts: Int,
    val totalPosts: Int,
    val scanComplete: Boolean,
    val error: String?,
    val found: Int,
    val selected: Int,
    val downloaded: Int,
    val duplicates: Int,
    val failed: Int,
    val createdAt: Long,
)

data class VkStoredImage(
    val jobId: String,
    val candidate: VkImageCandidate,
    val selected: Boolean,
    val phase: VkItemPhase,
    val savedUri: String?,
)

/** Independent schema: changes here cannot migrate or erase the existing download history. */
class VkCommunityDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "vk-community-images.db", null, 2,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE jobs (
            id TEXT PRIMARY KEY, link TEXT NOT NULL, start_date TEXT NOT NULL,
            end_date TEXT NOT NULL, zone_id TEXT NOT NULL, phase TEXT NOT NULL,
            offset_value INTEGER NOT NULL DEFAULT 0, scanned_posts INTEGER NOT NULL DEFAULT 0,
            total_posts INTEGER NOT NULL DEFAULT 0, scan_complete INTEGER NOT NULL DEFAULT 0,
            error TEXT, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE images (
            job_id TEXT NOT NULL, photo_key TEXT NOT NULL, post_key TEXT NOT NULL,
            post_date INTEGER NOT NULL, url TEXT NOT NULL, width INTEGER NOT NULL,
            height INTEGER NOT NULL, selected INTEGER NOT NULL DEFAULT 1,
            phase TEXT NOT NULL DEFAULT 'FOUND', sha256 TEXT, saved_uri TEXT, error TEXT,
            ad_status TEXT NOT NULL DEFAULT 'NONE',
            PRIMARY KEY (job_id, photo_key), FOREIGN KEY (job_id) REFERENCES jobs(id))""")
        db.execSQL("CREATE INDEX idx_images_job_phase ON images(job_id, phase, selected)")
        db.execSQL("""CREATE TABLE hashes (
            sha256 TEXT PRIMARY KEY, saved_uri TEXT NOT NULL, photo_key TEXT NOT NULL)""")
        db.execSQL("CREATE INDEX idx_images_photo ON images(photo_key)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE images ADD COLUMN ad_status TEXT NOT NULL DEFAULT 'NONE'")
        } else error("Unsupported VK image index migration $oldVersion->$newVersion")
    }

    fun createJob(link: String, period: VkDatePeriod, zoneId: String): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("jobs", null, ContentValues().apply {
            put("id", id); put("link", link); put("start_date", period.start.toString())
            put("end_date", period.endInclusive.toString()); put("zone_id", zoneId)
            put("phase", VkJobPhase.SCANNING.name); put("created_at", System.currentTimeMillis())
        })
        return id
    }

    fun latestJob(): VkCommunityJob? = readableDatabase.rawQuery(
        "SELECT id FROM jobs ORDER BY created_at DESC LIMIT 1", null,
    ).use { if (it.moveToFirst()) job(it.getString(0)) else null }

    fun job(id: String): VkCommunityJob? = readableDatabase.rawQuery("""
        SELECT j.id,j.link,j.start_date,j.end_date,j.zone_id,j.phase,j.offset_value,
          j.scanned_posts,j.total_posts,j.scan_complete,j.error,
          COUNT(i.photo_key),COALESCE(SUM(i.selected),0),
          COALESCE(SUM(CASE WHEN i.phase='DONE' THEN 1 ELSE 0 END),0),
          COALESCE(SUM(CASE WHEN i.phase='DUPLICATE' THEN 1 ELSE 0 END),0),
          COALESCE(SUM(CASE WHEN i.phase='ERROR' THEN 1 ELSE 0 END),0),j.created_at
        FROM jobs j LEFT JOIN images i ON i.job_id=j.id AND i.ad_status!='CONFIRMED'
        WHERE j.id=? GROUP BY j.id
    """.trimIndent(), arrayOf(id)).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.toJob()
    }

    fun jobs(limit: Int = 500): List<VkCommunityJob> {
        require(limit in 1..500)
        return readableDatabase.rawQuery("""
            SELECT j.id,j.link,j.start_date,j.end_date,j.zone_id,j.phase,j.offset_value,
              j.scanned_posts,j.total_posts,j.scan_complete,j.error,
              COUNT(i.photo_key),COALESCE(SUM(i.selected),0),
              COALESCE(SUM(CASE WHEN i.phase='DONE' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN i.phase='DUPLICATE' THEN 1 ELSE 0 END),0),
              COALESCE(SUM(CASE WHEN i.phase='ERROR' THEN 1 ELSE 0 END),0),j.created_at
            FROM jobs j LEFT JOIN images i ON i.job_id=j.id AND i.ad_status!='CONFIRMED'
            GROUP BY j.id
            ORDER BY j.created_at DESC LIMIT ?
        """.trimIndent(), arrayOf(limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toJob()) }
        }
    }

    /** Only completed jobs can be removed; downloaded files and the hash index stay intact. */
    fun deleteJob(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val phase = db.rawQuery("SELECT phase FROM jobs WHERE id=?", arrayOf(id)).use {
                if (it.moveToFirst()) VkJobPhase.valueOf(it.getString(0)) else null
            }
            if (phase == null || !VkHistoryPolicy.canDelete(phase)) return false
            db.delete("images", "job_id=?", arrayOf(id))
            val deleted = db.delete("jobs", "id=?", arrayOf(id)) > 0
            db.setTransactionSuccessful()
            return deleted
        } finally { db.endTransaction() }
    }

    fun clearHistory(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = arrayOf(VkJobPhase.SCANNING.name, VkJobPhase.DOWNLOADING.name)
            val removed = db.rawQuery("SELECT COUNT(*) FROM jobs WHERE phase NOT IN (?,?)", active)
                .use { if (it.moveToFirst()) it.getInt(0) else 0 }
            db.execSQL("DELETE FROM images WHERE job_id IN " +
                "(SELECT id FROM jobs WHERE phase NOT IN (?,?))", active)
            db.delete("jobs", "phase NOT IN (?,?)", active)
            db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }

    fun addPage(jobId: String, images: List<VkImageCandidate>, nextOffset: Int, total: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val active = db.rawQuery("SELECT 1 FROM jobs WHERE id=? AND phase='SCANNING'",
                arrayOf(jobId)).use { it.moveToFirst() }
            if (!active) return
            for (candidate in images) db.insertWithOnConflict("images", null, ContentValues().apply {
                put("job_id", jobId); put("photo_key", candidate.photoKey)
                put("post_key", candidate.postKey); put("post_date", candidate.postEpochSecond)
                put("url", candidate.url); put("width", candidate.width); put("height", candidate.height)
                put("ad_status", candidate.adStatus.name)
                put("selected", if (candidate.adStatus == VkAdStatus.CONFIRMED) 0 else 1)
                put("phase", VkItemPhase.FOUND.name)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.execSQL("UPDATE jobs SET offset_value=?,scanned_posts=?,total_posts=? WHERE id=? AND phase='SCANNING'",
                arrayOf(nextOffset, nextOffset, total, jobId))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun phase(jobId: String, phase: VkJobPhase, error: String? = null) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("phase", phase.name); put("error", error)
            if (phase == VkJobPhase.READY) put("scan_complete", 1)
        }, "id=?", arrayOf(jobId))
    }

    fun select(jobId: String, photoKey: String, selected: Boolean) {
        writableDatabase.execSQL("UPDATE images SET selected=? WHERE job_id=? AND photo_key=? " +
            "AND ad_status!='CONFIRMED' AND phase IN ('FOUND','ERROR')",
            arrayOf(if (selected) 1 else 0, jobId, photoKey))
    }

    fun selectAll(jobId: String, selected: Boolean) {
        writableDatabase.execSQL("UPDATE images SET selected=? WHERE job_id=? " +
            "AND phase IN ('FOUND','ERROR')" +
            if (selected) " AND ad_status!='CONFIRMED'" else "",
            arrayOf(if (selected) 1 else 0, jobId))
    }

    fun selectPost(jobId: String, postKey: String, selected: Boolean) {
        writableDatabase.execSQL("UPDATE images SET selected=? WHERE job_id=? AND post_key=? " +
            "AND ad_status!='CONFIRMED' AND phase IN ('FOUND','ERROR')",
            arrayOf(if (selected) 1 else 0, jobId, postKey))
    }

    fun images(jobId: String, offset: Int = 0, limit: Int = 60): List<VkStoredImage> {
        require(offset >= 0 && limit in 1..100)
        return readableDatabase.rawQuery("""SELECT photo_key,post_key,post_date,url,width,height,
            selected,phase,saved_uri,ad_status FROM images WHERE job_id=? AND ad_status!='CONFIRMED'
            ORDER BY post_date DESC,post_key DESC,photo_key
            LIMIT ? OFFSET ?""", arrayOf(jobId, limit.toString(), offset.toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toImage(jobId))
            }
        }
    }

    fun nextDownloads(jobId: String, limit: Int = 20): List<VkStoredImage> =
        readableDatabase.rawQuery("""SELECT photo_key,post_key,post_date,url,width,height,
            selected,phase,saved_uri,ad_status FROM images WHERE job_id=? AND selected=1
            AND ad_status!='CONFIRMED'
            AND phase='FOUND' ORDER BY post_date DESC,photo_key LIMIT ?""",
            arrayOf(jobId, limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toImage(jobId)) }
        }

    fun completedSelected(jobId: String, afterPhotoKey: String, limit: Int = 100): List<VkStoredImage> {
        require(limit in 1..100)
        return readableDatabase.rawQuery("""SELECT photo_key,post_key,post_date,url,width,height,
            selected,phase,saved_uri,ad_status FROM images WHERE job_id=? AND photo_key>?
            AND selected=1 AND ad_status!='CONFIRMED' AND phase IN ('DONE','DUPLICATE')
            ORDER BY photo_key LIMIT ?""",
            arrayOf(jobId, afterPhotoKey, limit.toString())).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.toImage(jobId)) }
        }
    }

    fun requeueMissing(jobId: String, photoKey: String): Boolean =
        writableDatabase.update("images", ContentValues().apply {
            put("phase", VkItemPhase.FOUND.name)
            putNull("saved_uri"); putNull("sha256"); putNull("error")
        }, "job_id=? AND photo_key=? AND selected=1 AND phase IN ('DONE','DUPLICATE')",
            arrayOf(jobId, photoKey)) > 0

    fun itemPhase(jobId: String, photoKey: String, phase: VkItemPhase,
        sha: String? = null, savedUri: String? = null, error: String? = null) {
        writableDatabase.update("images", ContentValues().apply {
            put("phase", phase.name); put("sha256", sha); put("saved_uri", savedUri)
            put("error", error)
        }, "job_id=? AND photo_key=?", arrayOf(jobId, photoKey))
    }

    fun hashUri(sha: String): String? = readableDatabase.rawQuery(
        "SELECT saved_uri FROM hashes WHERE sha256=?", arrayOf(sha),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun putHash(sha: String, uri: String, photoKey: String) {
        writableDatabase.insertWithOnConflict("hashes", null, ContentValues().apply {
            put("sha256", sha); put("saved_uri", uri); put("photo_key", photoKey)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun removeHash(sha: String) {
        writableDatabase.delete("hashes", "sha256=?", arrayOf(sha))
    }

    fun savedPhotoUri(photoKey: String): String? = readableDatabase.rawQuery(
        "SELECT saved_uri FROM images WHERE photo_key=? AND phase='DONE' AND saved_uri IS NOT NULL LIMIT 1",
        arrayOf(photoKey),
    ).use { if (it.moveToFirst()) it.getString(0) else null }

    fun retryFailed(jobId: String) {
        writableDatabase.execSQL("UPDATE images SET phase='FOUND',error=NULL " +
            "WHERE job_id=? AND phase='ERROR'", arrayOf(jobId))
    }

    fun resetInterrupted(jobId: String) {
        writableDatabase.execSQL("UPDATE images SET phase='ERROR',error='Загрузка прервана' " +
            "WHERE job_id=? AND phase='DOWNLOADING'", arrayOf(jobId))
    }

    private fun Cursor.toImage(jobId: String): VkStoredImage = VkStoredImage(
        jobId,
        VkImageCandidate(getString(0), getString(1), getLong(2), getString(3), getInt(4), getInt(5),
            VkAdStatus.valueOf(getString(9))),
        getInt(6) != 0, VkItemPhase.valueOf(getString(7)), stringOrNull(8),
    )

    private fun Cursor.toJob(): VkCommunityJob = VkCommunityJob(
        getString(0), getString(1), getString(2), getString(3), getString(4),
        VkJobPhase.valueOf(getString(5)), getInt(6), getInt(7), getInt(8), getInt(9) != 0,
        stringOrNull(10), getInt(11), getInt(12), getInt(13), getInt(14), getInt(15), getLong(16),
    )

    private fun Cursor.stringOrNull(index: Int): String? = if (isNull(index)) null else getString(index)
}
