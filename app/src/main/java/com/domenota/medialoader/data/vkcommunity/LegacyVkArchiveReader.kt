package com.domenota.medialoader.data.vkcommunity

import android.content.Context
import android.database.sqlite.SQLiteDatabase

data class LegacyVkArchiveSummary(val posts: Int, val fileReferences: Int)
data class LegacyVkArchivePost(val key: String, val dateEpochSecond: Long,
    val snippet: String, val fileReferences: Int)

/** Read only compatibility view for the experimental 1.7.16 archive. */
class LegacyVkArchiveReader(private val context: Context) {
    fun summary(): LegacyVkArchiveSummary? = runCatching {
        open()?.use { db ->
            db.rawQuery("SELECT COUNT(*),COALESCE(SUM(file_count),0) FROM posts", null).use { cursor ->
                if (!cursor.moveToFirst()) null else LegacyVkArchiveSummary(
                    cursor.getInt(0), cursor.getInt(1))
            }
        }
    }.getOrNull()

    fun posts(offset: Int, limit: Int = 20): List<LegacyVkArchivePost> {
        require(offset >= 0 && limit in 1..50)
        return runCatching { open()?.use { db ->
            db.rawQuery("SELECT key,date_sec,snippet,file_count FROM posts " +
                "ORDER BY date_sec DESC LIMIT ? OFFSET ?",
                arrayOf(limit.toString(), offset.toString())).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(LegacyVkArchivePost(
                        cursor.getString(0), cursor.getLong(1), cursor.getString(2), cursor.getInt(3)))
                }
            }
        } }.getOrNull() ?: emptyList()
    }

    private fun open(): SQLiteDatabase? {
        val file = context.getDatabasePath("vk-wall-archive.db")
        if (!file.isFile) return null
        return runCatching {
            val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
            val valid = db.rawQuery("PRAGMA user_version", null).use { cursor ->
                cursor.moveToFirst() && cursor.getInt(0) == 1
            } && db.rawQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='posts'",
                null).use { it.moveToFirst() }
            if (!valid) { db.close(); null } else db
        }.getOrNull()
    }
}
