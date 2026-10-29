package com.domenota.medialoader.data.instagramprofile

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.time.LocalDate
import java.util.UUID
import org.json.JSONArray

enum class InstagramProfilePhase { SCANNING, READY, DOWNLOADING, FINISHED, ERROR, CANCELLED }

data class InstagramProfileJob(
    val id: String, val username: String, val startDate: String?, val endDate: String?,
    val includeReels: Boolean, val favoriteCheck: Boolean, val userId: String?,
    val profileName: String?, val avatarUrl: String?, val feedCursor: String?,
    val reelsCursor: String?, val feedDone: Boolean, val reelsDone: Boolean,
    val nextReels: Boolean, val scanned: Int, val batchLimit: Int, val reviewed: Boolean,
    val phase: InstagramProfilePhase,
    val error: String?, val createdAt: Long,
) {
    val scanComplete: Boolean get() = feedDone && (reelsDone || !includeReels)
    val periodStart: LocalDate? get() = startDate?.let(LocalDate::parse)
    val periodEnd: LocalDate? get() = endDate?.let(LocalDate::parse)
}

data class InstagramStoredPost(
    val jobId: String, val post: InstagramProfilePost, val selected: Boolean,
    val taskIds: List<String>, val excludedIds: Set<String>,
)

/** Profile jobs are independent from VK and download-history databases. */
class InstagramProfileStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext, "instagram-profile.db", null, 1,
) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE jobs (
            id TEXT PRIMARY KEY, username TEXT NOT NULL, start_date TEXT, end_date TEXT,
            reels INTEGER NOT NULL, favorite_check INTEGER NOT NULL, user_id TEXT,
            profile_name TEXT, avatar_url TEXT, feed_cursor TEXT, reels_cursor TEXT,
            feed_done INTEGER NOT NULL DEFAULT 0, reels_done INTEGER NOT NULL DEFAULT 0,
            next_reels INTEGER NOT NULL DEFAULT 0, scanned INTEGER NOT NULL DEFAULT 0,
            batch_limit INTEGER NOT NULL DEFAULT 300, reviewed INTEGER NOT NULL DEFAULT 0,
            phase TEXT NOT NULL,
            error TEXT, created_at INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE posts (
            job_id TEXT NOT NULL, code TEXT NOT NULL, post_date INTEGER NOT NULL,
            video INTEGER NOT NULL, reel INTEGER NOT NULL, preview_url TEXT, media_count INTEGER NOT NULL,
            selected INTEGER NOT NULL DEFAULT 1, task_ids TEXT NOT NULL DEFAULT '[]',
            excluded_ids TEXT NOT NULL DEFAULT '[]',
            PRIMARY KEY(job_id,code))""")
        db.execSQL("CREATE INDEX idx_ig_posts_job_date ON posts(job_id,post_date DESC)")
        db.execSQL("CREATE TABLE seen (username TEXT NOT NULL, code TEXT NOT NULL, PRIMARY KEY(username,code))")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) =
        error("Unsupported Instagram profile database migration $oldVersion->$newVersion")

    fun create(username: String, start: LocalDate?, end: LocalDate?, includeReels: Boolean,
        favoriteCheck: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        writableDatabase.insertOrThrow("jobs", null, ContentValues().apply {
            put("id", id); put("username", username); put("start_date", start?.toString())
            put("end_date", end?.toString()); put("reels", includeReels.asInt())
            put("favorite_check", favoriteCheck.asInt()); put("phase", InstagramProfilePhase.SCANNING.name)
            put("created_at", System.currentTimeMillis())
        })
        return id
    }

    fun job(id: String): InstagramProfileJob? = readableDatabase.rawQuery(
        "SELECT * FROM jobs WHERE id=?", arrayOf(id)).use {
        if (it.moveToFirst()) it.toJob() else null
    }

    fun jobs(limit: Int = 50): List<InstagramProfileJob> = readableDatabase.rawQuery(
        "SELECT * FROM jobs ORDER BY created_at DESC LIMIT ?", arrayOf(limit.toString())).use { cursor ->
        buildList { while (cursor.moveToNext()) add(cursor.toJob()) }
    }

    fun posts(jobId: String): List<InstagramStoredPost> = readableDatabase.rawQuery(
        "SELECT code,post_date,video,reel,preview_url,media_count,selected,task_ids,excluded_ids FROM posts WHERE job_id=? ORDER BY post_date DESC,code DESC",
        arrayOf(jobId)).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val ids = runCatching { JSONArray(cursor.getString(7)) }.getOrDefault(JSONArray())
                val excluded = runCatching { JSONArray(cursor.getString(8)) }.getOrDefault(JSONArray())
                add(InstagramStoredPost(jobId, InstagramProfilePost(cursor.getString(0),
                    cursor.getLong(1), cursor.getInt(2) != 0, cursor.getString(4), cursor.getInt(5),
                    cursor.getInt(3) != 0),
                    cursor.getInt(6) != 0, (0 until ids.length()).map(ids::getString),
                    (0 until excluded.length()).map(excluded::getString).toSet()))
            }
        }
    }

    fun setProfile(id: String, info: InstagramProfileInfo) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("user_id", info.id); put("profile_name", info.name); put("avatar_url", info.avatarUrl)
        }, "id=?", arrayOf(id))
    }

    fun addPage(id: String, username: String, posts: List<InstagramProfilePost>,
        reels: Boolean, cursor: String?, finished: Boolean, scanned: Int) {
        val db = writableDatabase
        val favoriteCheck = job(id)?.favoriteCheck == true
        db.beginTransaction()
        try {
            posts.forEach { post ->
                if (favoriteCheck && hasSeen(db, username, post.code)) return@forEach
                db.insertWithOnConflict("posts", null, ContentValues().apply {
                    put("job_id", id); put("code", post.code); put("post_date", post.epochSecond)
                    put("video", post.video.asInt()); put("reel", post.reel.asInt()); put("preview_url", post.previewUrl)
                    put("media_count", post.mediaCount)
                    put("selected", if (hasSeen(db, username, post.code)) 0 else 1)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (post.video || post.previewUrl != null) db.execSQL(
                    "UPDATE posts SET video=MAX(video,?),reel=MAX(reel,?),preview_url=COALESCE(preview_url,?),media_count=MAX(media_count,?) WHERE job_id=? AND code=?",
                    arrayOf(post.video.asInt(), post.reel.asInt(), post.previewUrl, post.mediaCount, id, post.code))
            }
            db.update("jobs", ContentValues().apply {
                put(if (reels) "reels_cursor" else "feed_cursor", cursor)
                put(if (reels) "reels_done" else "feed_done", finished.asInt())
                put("next_reels", (!reels).asInt())
                put("scanned", scanned)
            }, "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun phase(id: String, value: InstagramProfilePhase, error: String? = null) {
        writableDatabase.update("jobs", ContentValues().apply {
            put("phase", value.name); put("error", error)
        }, "id=?", arrayOf(id))
    }

    fun continueBatch(id: String) {
        val job = job(id) ?: return
        writableDatabase.update("jobs", ContentValues().apply {
            put("batch_limit", posts(id).size + InstagramProfileScanPolicy.DEFAULT_BATCH_SIZE)
            put("phase", InstagramProfilePhase.SCANNING.name); putNull("error")
        }, "id=?", arrayOf(id))
    }

    fun select(id: String, code: String, selected: Boolean) {
        writableDatabase.update("posts", ContentValues().apply {
            put("selected", selected.asInt())
            if (selected) put("excluded_ids", "[]")
        },
            "job_id=? AND code=?", arrayOf(id, code))
    }

    fun selectAll(id: String, selected: Boolean) {
        writableDatabase.update("posts", ContentValues().apply {
            put("selected", selected.asInt())
            if (selected) put("excluded_ids", "[]")
        },
            "job_id=?", arrayOf(id))
    }

    fun selectVisual(id: String, code: String, visualId: String, selected: Boolean,
        allIds: List<String>) {
        val stored = posts(id).firstOrNull { it.post.code == code } ?: return
        val excluded = if (stored.selected) stored.excludedIds.toMutableSet()
            else allIds.toMutableSet()
        if (selected) excluded.remove(visualId) else excluded.add(visualId)
        writableDatabase.update("posts", ContentValues().apply {
            put("excluded_ids", JSONArray(excluded.toList()).toString())
            put("selected", (excluded.size < allIds.size).asInt())
        }, "job_id=? AND code=?", arrayOf(id, code))
    }

    fun setTasks(id: String, code: String, taskIds: List<String>) {
        writableDatabase.update("posts", ContentValues().apply { put("task_ids", JSONArray(taskIds).toString()) },
            "job_id=? AND code=?", arrayOf(id, code))
    }

    fun markReviewed(id: String) {
        val job = job(id) ?: return
        val db = writableDatabase
        db.beginTransaction()
        try {
            posts(id).forEach { post -> db.insertWithOnConflict("seen", null,
                ContentValues().apply { put("username", job.username); put("code", post.post.code) },
                SQLiteDatabase.CONFLICT_IGNORE) }
            db.update("jobs", ContentValues().apply { put("reviewed", 1) }, "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun delete(id: String): Boolean {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val removed = db.delete("jobs", "id=?", arrayOf(id)) > 0
            db.delete("posts", "job_id=?", arrayOf(id))
            db.setTransactionSuccessful()
            return removed
        } finally { db.endTransaction() }
    }

    private fun hasSeen(db: SQLiteDatabase, username: String, code: String): Boolean = db.rawQuery(
        "SELECT 1 FROM seen WHERE username=? AND code=?", arrayOf(username, code)).use { it.moveToFirst() }

    private fun Cursor.toJob() = InstagramProfileJob(
        getString(getColumnIndexOrThrow("id")), getString(getColumnIndexOrThrow("username")),
        getString(getColumnIndexOrThrow("start_date")), getString(getColumnIndexOrThrow("end_date")),
        int("reels") != 0, int("favorite_check") != 0, string("user_id"), string("profile_name"),
        string("avatar_url"), string("feed_cursor"), string("reels_cursor"),
        int("feed_done") != 0, int("reels_done") != 0, int("next_reels") != 0,
        int("scanned"), int("batch_limit"), int("reviewed") != 0,
        InstagramProfilePhase.valueOf(string("phase")!!),
        string("error"), getLong(getColumnIndexOrThrow("created_at")),
    )

    private fun Cursor.int(column: String) = getInt(getColumnIndexOrThrow(column))
    private fun Cursor.string(column: String) = getString(getColumnIndexOrThrow(column))
    private fun Boolean.asInt() = if (this) 1 else 0
}
