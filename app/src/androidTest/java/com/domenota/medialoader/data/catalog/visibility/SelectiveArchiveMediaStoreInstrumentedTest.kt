package com.domenota.medialoader.data.catalog.visibility

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.media.MediaScannerConnection
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-only evidence experiment. Does NOT archive user content or enable the
 * retired v2 mover. Every write is confined to a fresh uniquely named disposable
 * Pictures/MLSelectiveArchiveProbe_<uuid> tree and app-created MediaStore rows.
 *
 * Run on Xiaomi with MANAGE_EXTERNAL_STORAGE already granted to the test APK.
 * A missing grant is a FAILED probe, not a silently skipped PASS.
 *
 * Even a PASS establishes only the measured OEM/build, not a universal Android
 * guarantee. Record model/Android build, run with / without .nomedia, and never
 * use this test as authorization for moving real files.
 */
@RunWith(AndroidJUnit4::class)
class SelectiveArchiveMediaStoreInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val resolver:ContentResolver get() = context.contentResolver
    private val mediaCollection:Uri get() =
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    @Test fun ordinaryMovePreservesMediaStoreIdentity() = runBlocking<Unit> {
        withContext(Dispatchers.IO) { probe(hiddenMarker=false) }
    }

    @Test fun nomediaMoveAndScanPreservesMediaStoreIdentityOrFailsExplicitly() = runBlocking<Unit> {
        withContext(Dispatchers.IO) { probe(hiddenMarker=true) }
    }

    private data class Row(
        val id:Long,val name:String,val relative:String,val size:Long,val pending:Boolean,val trashed:Boolean
    )

    private fun row(uri:Uri):Row? {
        val columns=arrayOf(
            MediaStore.MediaColumns._ID,MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.IS_PENDING,MediaStore.MediaColumns.IS_TRASHED)
        return resolver.query(uri,columns,null,null,null)?.use { c ->
            if(!c.moveToFirst()) return@use null
            check(c.count==1)
            Row(c.getLong(0),c.getString(1),c.getString(2),c.getLong(3),
                c.getInt(4)!=0,c.getInt(5)!=0)
        }
    }

    private fun imageBytes():ByteArray {
        val bmp=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        return try {
            bmp.eraseColor(0xff4070aa.toInt())
            java.io.ByteArrayOutputStream().use {
                check(bmp.compress(Bitmap.CompressFormat.PNG,100,it))
                it.toByteArray()
            }
        } finally { bmp.recycle() }
    }

    private fun createSynthetic(name:String,relative:String,bytes:ByteArray):Uri {
        val values=ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(MediaStore.MediaColumns.MIME_TYPE,"image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH,relative)
            put(MediaStore.MediaColumns.IS_PENDING,1)
        }
        val uri=checkNotNull(resolver.insert(mediaCollection,values)) {
            "Cannot insert synthetic proof media"
        }
        try {
            resolver.openOutputStream(uri,"w")!!.use { it.write(bytes) }
            check(resolver.update(uri,ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING,0)
            },null,null)==1) { "Synthetic media was not published" }
            return uri
        } catch (error:Throwable) {
            runCatching { resolver.delete(uri,null,null) }
            throw error
        }
    }

    private fun move(uri:Uri,relative:String) {
        val updated=resolver.update(uri,ContentValues().apply {
            put(MediaStore.MediaColumns.RELATIVE_PATH,relative)
        },null,null)
        check(updated==1) { "MediaStore refused RELATIVE_PATH update; changed=$updated" }
    }

    private fun scan(path:File) {
        val latch=CountDownLatch(1)
        MediaScannerConnection.scanFile(context,arrayOf(path.absolutePath),arrayOf("image/png")) { _,_ ->
            latch.countDown()
        }
        check(latch.await(15,TimeUnit.SECONDS)) { "MediaScanner did not finish in time" }
    }

    private fun payload(uri:Uri):ByteArray =
        checkNotNull(resolver.openInputStream(uri)) { "Original URI is unreadable" }.use { it.readBytes() }

    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun probe(hiddenMarker:Boolean) {
        check(Build.VERSION.SDK_INT>=30) { "Android 11+ required" }
        check(Environment.isExternalStorageManager()) {
            "Explicit all-files access required for isolated .nomedia experiment"
        }
        val id=UUID.randomUUID().toString().replace("-","")
        val root=File(Environment.getExternalStorageDirectory(),"Pictures/MLSelectiveArchiveProbe_$id")
        val source=File(root,"source")
        val target=File(root,"target")
        val inputRelative="Pictures/${root.name}/source/"
        val targetRelative="Pictures/${root.name}/target/"
        val selectedName="selected_$id.png"
        val keeperName="keeper_$id.png"
        val expected=imageBytes()
        val selectedSource=File(source,selectedName)
        val selectedTarget=File(target,selectedName)
        val keeperSource=File(source,keeperName)
        val marker=File(target,".nomedia")
        var selected:Uri?=null
        var keeper:Uri?=null
        check(!root.exists()) { "Probe path already exists" }
        try {
            check(source.mkdirs() && target.mkdirs()) { "Cannot create disposable probe directories" }
            // Create the marker ONLY in the empty, unique target directory.
            if(hiddenMarker) {
                check(target.listFiles()?.isEmpty()==true)
                check(marker.createNewFile() && marker.isFile && marker.length()==0L)
            }
            selected=createSynthetic(selectedName,inputRelative,expected)
            keeper=createSynthetic(keeperName,inputRelative,expected)
            val selectedUri=requireNotNull(selected)
            val keeperUri=requireNotNull(keeper)
            val original=checkNotNull(row(selectedUri))
            val neighbor=checkNotNull(row(keeperUri))
            val originalId=ContentUris.parseId(selectedUri)
            val neighborId=ContentUris.parseId(keeperUri)
            check(original.id==originalId && neighbor.id==neighborId)
            check(original.relative==inputRelative && neighbor.relative==inputRelative)
            val originalTime=selectedSource.lastModified()
            val neighborTime=keeperSource.lastModified()
            check(originalTime>0 && neighborTime>0)
            assertArrayEquals(expected,payload(selectedUri))
            move(selectedUri,targetRelative)
            check(selectedTarget.isFile && selectedTarget.length()==expected.size.toLong()) {
                "Synthetic selected file did not reach intended target"
            }
            if(hiddenMarker) scan(selectedTarget)

            // The URI may disappear from a normal MediaStore query after
            // .nomedia reindexing. DO NOT repair that by inserting a new row.
            val hiddenRow=row(selectedUri)
            Log.i("MLSelectiveArchiveProbe","hiddenMarker=$hiddenMarker originalIdPresent=${hiddenRow?.id==originalId}")
            check(hiddenRow?.id==originalId) {
                "Selected original MediaStore ID disappeared under .nomedia"
            }
            move(selectedUri,inputRelative)
            if(hiddenMarker) scan(selectedSource)
            val restored=checkNotNull(row(selectedUri)) {
                "Restored synthetic MediaStore URI no longer resolves"
            }
            val neighborAfter=checkNotNull(row(keeperUri))
            assertEquals("Restored ID must be exactly original",originalId,restored.id)
            assertEquals("Restored URI folder changed",inputRelative,restored.relative)
            assertEquals("Original filename changed",selectedName,restored.name)
            assertFalse(restored.pending)
            assertFalse(restored.trashed)
            assertEquals(expected.size.toLong(),restored.size)
            assertArrayEquals(sha(expected),sha(payload(selectedUri)))
            assertEquals("Selected mtime changed",originalTime,selectedSource.lastModified())
            assertEquals("Unselected file ID changed",neighborId,neighborAfter.id)
            assertEquals(inputRelative,neighborAfter.relative)
            assertEquals(keeperName,neighborAfter.name)
            assertEquals("Unselected mtime changed",neighborTime,keeperSource.lastModified())
            assertArrayEquals(expected,payload(keeperUri))
            Log.i("MLSelectiveArchiveProbe",
                "PASS hiddenMarker=$hiddenMarker sameId=true sameBytes=true sameMtime=true neighborPreserved=true")
        } finally {
            // CLEANUP IS SCOPED to our random synthetic names/URIs ONLY.
            // Never recursively delete user directories or unknown contents.
            selected?.let { runCatching { resolver.delete(it,null,null) } }
            keeper?.let { runCatching { resolver.delete(it,null,null) } }
            for(file in listOf(selectedSource,selectedTarget,keeperSource,marker)) {
                if(file.exists() && file.isFile) runCatching { file.delete() }
            }
            source.delete();target.delete();root.delete()
            if(root.exists()) {
                Log.w("MLSelectiveArchiveProbe","Disposable probe root requires manual inspection; no recursive deletion")
            }
        }
    }
}
