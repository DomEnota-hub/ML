package com.domenota.medialoader.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal val PRIVATE_HISTORY_MIGRATION = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE downloads ADD COLUMN isPrivate INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE downloads ADD COLUMN privateSourceUri TEXT")
    }
}
