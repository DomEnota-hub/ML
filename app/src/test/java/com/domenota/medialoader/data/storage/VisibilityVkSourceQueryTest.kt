package com.domenota.medialoader.data.storage

import android.database.sqlite.SQLiteDatabase
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Avoid weakening source-reference checks while removing JSON decoding work. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class VisibilityVkSourceQueryTest {
    @Test fun exactUriInEitherVkTableMustBlockRemoval() {
        val db=SQLiteDatabase.create(null)
        try {
            db.execSQL("CREATE TABLE images (job_id TEXT, photo_key TEXT, saved_uri TEXT)")
            db.execSQL("CREATE TABLE hashes (sha256 TEXT, photo_key TEXT, saved_uri TEXT)")
            val old="content://media/external_primary/images/media/44"
            val other="content://media/external_primary/images/media/440"
            db.execSQL("INSERT INTO images (job_id,photo_key,saved_uri) VALUES ('j','p',?)",
                arrayOf(other))
            assertFalse(VisibilityVkSourceQuery.exists(db,"images",old))
            db.execSQL("INSERT INTO hashes (sha256,photo_key,saved_uri) VALUES ('s','p',?)",
                arrayOf(old))
            assertTrue(VisibilityVkSourceQuery.exists(db,"hashes",old))
            assertFalse(VisibilityVkSourceQuery.exists(db,"images",old))
            db.execSQL("INSERT INTO images (job_id,photo_key,saved_uri) VALUES ('j2','p2',?)",
                arrayOf(old))
            assertTrue(VisibilityVkSourceQuery.exists(db,"images",old))
            db.execSQL("DELETE FROM images WHERE saved_uri=?",arrayOf(old))
            db.execSQL("DELETE FROM hashes WHERE saved_uri=?",arrayOf(old))
            assertFalse(VisibilityVkSourceQuery.exists(db,"images",old))
            assertFalse(VisibilityVkSourceQuery.exists(db,"hashes",old))
        } finally { db.close() }
    }

    @Test fun unknownTableCannotBeQueried() {
        val db=SQLiteDatabase.create(null)
        try {
            assertTrue(runCatching { VisibilityVkSourceQuery.exists(db,"downloads","uri") }.isFailure)
        } finally { db.close() }
    }
}
