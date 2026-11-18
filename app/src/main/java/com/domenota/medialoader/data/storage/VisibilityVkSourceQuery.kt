package com.domenota.medialoader.data.storage

import android.database.sqlite.SQLiteDatabase

/**
 * Exact source locator existence proof for the two known VK tables.
 * A LIMIT 1 query is equivalent to requiring zero rows, without decoding all
 * columns into JSON at each visibility-move safety boundary.
 */
internal object VisibilityVkSourceQuery {
    fun exists(database: SQLiteDatabase, table: String, savedUri: String): Boolean {
        require(table == "images" || table == "hashes")
        return database.rawQuery("SELECT 1 FROM $table WHERE saved_uri=? LIMIT 1",arrayOf(savedUri)).use {
            it.moveToFirst()
        }
    }
}
