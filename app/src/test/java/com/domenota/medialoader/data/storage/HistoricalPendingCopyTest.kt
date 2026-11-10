package com.domenota.medialoader.data.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.Room
import com.domenota.medialoader.core.database.HistoryDatabase
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.security.MessageDigest
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver

/** Models a real pending file whose provider index still reports zero bytes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HistoricalPendingCopyTest {
    private lateinit var history: HistoryDatabase
    private fun fixture(block: suspend (HistoricalMediaRecovery, JSONObject, Provider) -> Unit) = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val db = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        history = db
        val file = File.createTempFile("pending-copy", ".jpg", context.cacheDir)
        val bytes = "synthetic media bytes".toByteArray()
        file.writeBytes(bytes)
        val provider = Provider(file, context.packageName)
        provider.attachInfo(context, ProviderInfo().apply { authority = "media"; exported = true })
        ShadowContentResolver.registerProviderInternal("media", provider)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val op = JSONObject().put("newUri", "content://media/external/images/media/43")
            .put("oldUri", "content://media/external/images/media/42").put("name", "fixture.jpg")
            .put("mime", "image/jpeg").put("newBytes", bytes.size).put("newHash", hash)
            .put("historical", 1700000000000L)
        try { block(HistoricalMediaRecovery(context, db), op, provider) }
        finally { db.close(); file.delete() }
    }

    private suspend fun verify(engine: HistoricalMediaRecovery, op: JSONObject, published: Boolean = false,
        date: Boolean = false) = suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
        val method = HistoricalMediaRecovery::class.java.getDeclaredMethod("verifyNew", JSONObject::class.java,
            Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Continuation::class.java)
        method.isAccessible = true
        try { method.invoke(engine, op, published, date, continuation) }
        catch (error: InvocationTargetException) { throw error.targetException }
    }

    @Test fun pendingIndexMayRemainZeroAfterSuccessfulWrite() = fixture { engine, op, provider ->
        assertEquals(0L, provider.indexedBytes)
        verify(engine, op)
        assertTrue(provider.opens > 0)
    }

    @Test fun actualTruncationStillStopsRecovery() = fixture { engine, op, provider ->
        provider.indexedBytes = op.getLong("newBytes")
        provider.file.writeBytes(byteArrayOf(1))
        try { verify(engine, op); fail("Truncated copy accepted") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("размер", true)) }
    }

    @Test fun sameLengthCorruptionStillStopsRecovery() = fixture { engine, op, provider ->
        provider.indexedBytes = op.getLong("newBytes")
        provider.file.writeBytes(ByteArray(op.getInt("newBytes")) { 1 })
        try { verify(engine, op); fail("Corrupt copy accepted") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("SHA-256")) }
    }

    @Test fun publishedCopyNeedsConfirmedIndexBeforeFinalCommit() = fixture { engine, op, provider ->
        provider.pending = false
        try { verify(engine, op, published = true, date = true); fail("Unconfirmed index accepted") }
        catch (_: IllegalStateException) { }
    }

    @Test fun publicationWaitsForIndexWithoutRejectingCorrectFile() = fixture { engine, op, provider ->
        provider.pending = false
        provider.refreshIndexOnSecondRead = true
        suspendCoroutineUninterceptedOrReturn<Any?> { continuation ->
            val method = HistoricalMediaRecovery::class.java.getDeclaredMethod("verifyPublishedWithRetry",
                JSONObject::class.java, Continuation::class.java)
            method.isAccessible = true
            try { method.invoke(engine, op, continuation) }
            catch (error: InvocationTargetException) { throw error.targetException }
        }
        assertEquals(op.getLong("newBytes"), provider.indexedBytes)
    }

    private fun row(op: JSONObject) = DownloadEntity("stable-id", "https://example.invalid/fixture",
        "fixture.jpg", MediaType.PHOTO, DownloadState.COMPLETED, op.getString("newUri"),
        op.getLong("newBytes"), 1700000010000L)

    @Test fun listDoesNotOpenEveryFileOrSnapshotEveryReferenceStore() = fixture { engine, op, provider ->
        provider.pending = false
        provider.indexedBytes = op.getLong("newBytes")
        history.downloadDao().upsert(row(op))
        assertEquals(listOf("stable-id"), engine.inspect().items.map { it.downloadId })
        assertEquals(0, provider.opens)
    }

    @Test fun previewStillExcludesPrivateProvenanceSharedWithAnotherHistoryRow() = fixture { engine, op, provider ->
        provider.pending = false
        provider.indexedBytes = op.getLong("newBytes")
        val original = row(op)
        history.downloadDao().upsert(original)
        history.downloadDao().upsert(original.copy(id = "private-id", isPrivate = true,
            savedUri = "content://example.invalid/private", privateSourceUri = original.savedUri))
        assertTrue(engine.inspect().items.isEmpty())
        assertEquals(0, provider.opens)
    }

    class Provider(val file: File, private val owner: String) : ContentProvider() {
        var indexedBytes = 0L
        var pending = true
        var opens = 0
        var refreshIndexOnSecondRead = false
        private var metadataReads = 0
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
            selectionArgs: Array<out String>?, sortOrder: String?): Cursor =
            MatrixCursor(projection!!).apply {
                if ("_size" in projection && ++metadataReads >= 2 && refreshIndexOnSecondRead)
                    indexedBytes = file.length()
                addRow(projection.map { column -> when (column) {
                    "_id" -> 43L
                    "owner_package_name" -> owner
                    "relative_path" -> "Pictures/MediaLoader/"
                    "_display_name" -> "fixture.jpg"
                    "mime_type" -> "image/jpeg"
                    "_size" -> indexedBytes
                    "is_pending" -> if (pending) 1 else 0
                    "datetaken" -> 1700000000000L
                    else -> 0L
                } })
            }
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
            opens++
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri) = "image/jpeg"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = error("No writes in verification")
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = error("No deletes")
        override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = error("No updates")
    }
}
