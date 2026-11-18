package com.domenota.medialoader.data.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.AtomicFile
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.roundToInt

/** A display copy, never evidence that its original can be opened. All identities
 * are retained so a later hide/private transition also blocks the offline copy. */
internal data class LastCollectionCover(
    val slotToken:String,
    val contentToken:String,
    val source:String,
    val identities:Set<String>,
    val lastVerifiedAt:Long,
)
internal data class LastCollectionCoverFrame(val metadata:LastCollectionCover,val bitmap:Bitmap)

/** Permission to read phone media deliberately does not participate here. Unknown
 * ML privacy, an unknown identity or a changed authoritative slot fail closed. */
internal fun lastCollectionCoverAllowed(cover:LastCollectionCover,slot:String?,blocked:Set<String>?):Boolean =
    blocked!=null && slot!=null && slot==cover.slotToken && cover.identities.isNotEmpty() &&
        cover.identities.none { key -> key in blocked ||
            (key.startsWith("app:") && key.removePrefix("app:") in blocked) ||
            (key.startsWith("locator:") && blocked.any { key.startsWith("locator:$it:") }) } &&
        collectionCoverAllowed(cover.source,blocked)

/** One atomic file contains both the thumbnail and metadata. A failed compression,
 * interrupted write or process death cannot publish mismatching image/metadata.
 * noBackupFilesDir avoids backup/export; neither originals nor custom selections
 * are ever evicted. Every entry is bounded before allocating/decoding. IO only. */
internal class LastCollectionCoverStore(context:Context,
    private val maxEntries:Int=512,private val maxBytes:Long=64L*1024*1024) {
    private val root=File(context.applicationContext.noBackupFilesDir,"last-collection-covers-v1")
    init { require(maxEntries>=1 && maxBytes>=MAX_FILE_BYTES) }
    private fun file(id:String):File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,160}")))
        return File(root,"$id.cover")
    }
    private fun key(id:String)=file(id).absolutePath
    // Compose only acquires this tiny cache lock, never the disk writer lock.
    fun peek(id:String):LastCollectionCoverFrame?=synchronized(memoryLock){memory.get(key(id))}
    fun read(id:String):LastCollectionCoverFrame?=synchronized(ioLock){
        peek(id)?.let{return@synchronized it}
        try {
            val atomic=AtomicFile(file(id))
            val entry=atomic.openRead().use { raw ->
                require(raw.channel.size() in 1..MAX_FILE_BYTES.toLong())
                val input=DataInputStream(raw)
                require(input.readInt()==MAGIC)
                val length=input.readInt();require(length in 1..MAX_METADATA_BYTES)
                val header=ByteArray(length).also(input::readFully)
                val json=JSONObject(String(header,Charsets.UTF_8))
                val identities=json.getJSONArray("identities")
                require(identities.length() in 1..256)
                val metadata=LastCollectionCover(json.getString("slot"),json.getString("content"),json.getString("source"),
                    (0 until identities.length()).map{identities.getString(it)}.toSet(),json.getLong("verified"))
                validate(metadata)
                val bytes=input.readBytes()
                require(bytes.size in 1..MAX_IMAGE_BYTES)
                val bounds=BitmapFactory.Options().apply{inJustDecodeBounds=true}
                BitmapFactory.decodeByteArray(bytes,0,bytes.size,bounds)
                require(bounds.outWidth in 1..SIDE && bounds.outHeight in 1..SIDE)
                val bitmap=requireNotNull(BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                LastCollectionCoverFrame(metadata,bitmap)
            }
            synchronized(memoryLock){memory.put(key(id),entry)}
            entry
        }catch(_:Exception){null}
    }
    /** False means the source's confirmed change token did not change: no writes
     * and no new bitmap/UI state. Temporary decode failures never call save. */
    fun save(id:String,metadata:LastCollectionCover,image:Bitmap):Boolean=synchronized(ioLock){
        validate(metadata)
        require(!image.isRecycled && image.width>0 && image.height>0)
        val old=read(id)
        if(old?.metadata?.let { it.slotToken==metadata.slotToken && it.contentToken==metadata.contentToken && it.identities==metadata.identities }==true)
            return@synchronized false
        check(root.mkdirs()||root.isDirectory)
        val scale=minOf(1f,SIDE.toFloat()/maxOf(image.width,image.height))
        // Own the retained bitmap; the decoder/caller may recycle its input.
        val preview=if(scale<1f)Bitmap.createScaledBitmap(image,(image.width*scale).roundToInt().coerceAtLeast(1),
            (image.height*scale).roundToInt().coerceAtLeast(1),true) else image.copy(Bitmap.Config.ARGB_8888,false)
        try {
            val encoded=ByteArrayOutputStream().use { bytes ->
                check(preview.compress(Bitmap.CompressFormat.JPEG,88,bytes))
                bytes.toByteArray().also{require(it.size in 1..MAX_IMAGE_BYTES)}
            }
            val json=JSONObject().put("slot",metadata.slotToken).put("content",metadata.contentToken)
                .put("source",metadata.source).put("identities",JSONArray(metadata.identities.sorted()))
                .put("verified",metadata.lastVerifiedAt)
            val header=json.toString().toByteArray(Charsets.UTF_8);require(header.size<=MAX_METADATA_BYTES)
            val atomic=AtomicFile(file(id))
            val output=atomic.startWrite()
            try {
                val data=DataOutputStream(output)
                data.writeInt(MAGIC);data.writeInt(header.size);data.write(header);data.write(encoded);data.flush()
                output.fd.sync()
                atomic.finishWrite(output)
            }catch(e:Exception){atomic.failWrite(output);throw e}
            synchronized(memoryLock){memory.put(key(id),LastCollectionCoverFrame(metadata,preview))}
            trim(id)
            true
        }catch(e:Exception){preview.recycle();throw e}
    }
    fun remove(id:String)=synchronized(ioLock){AtomicFile(file(id)).delete();synchronized(memoryLock){memory.remove(key(id))};Unit}
    private fun trim(keep:String) {
        // Recover legacy AtomicFile backups before counting the budget. Staging
        // bytes without a committed base are never a readable thumbnail.
        root.listFiles().orEmpty().filter{it.name.endsWith(".new")||it.name.endsWith(".bak")}.forEach { staged ->
            val base=File(root,staged.name.substringBeforeLast('.'))
            if(staged.name.endsWith(".bak"))runCatching{AtomicFile(base).openRead().close()}
            else staged.delete()
        }
        val entries=root.listFiles().orEmpty().filter{it.extension=="cover"}.sortedBy{it.lastModified()}
        var count=entries.size;var bytes=entries.sumOf{it.length()}
        for(entry in entries){
            if(count<=maxEntries && bytes<=maxBytes)break
            if(entry==file(keep))continue
            val size=entry.length()
            if(entry.delete()){count--;bytes-=size;synchronized(memoryLock){memory.remove(entry.absolutePath)}}
        }
    }

    companion object {
        private const val MAGIC=0x4d4c4301
        private const val SIDE=384
        private const val MAX_METADATA_BYTES=32*1024
        private const val MAX_IMAGE_BYTES=512*1024
        private const val MAX_FILE_BYTES=MAX_METADATA_BYTES+MAX_IMAGE_BYTES+8
        // Lock order is IO -> memory only. No cache operation enters IO.
        private val ioLock=Any()
        private val memoryLock=Any()
        private val memory=object:LruCache<String,LastCollectionCoverFrame>(16*1024*1024){
            override fun sizeOf(key:String,value:LastCollectionCoverFrame)=value.bitmap.allocationByteCount
        }
        fun clearMemory()=synchronized(memoryLock){memory.evictAll()}
        private fun validate(value:LastCollectionCover){
            require(value.slotToken.length in 1..256 && value.contentToken.length in 1..256 && value.source.length in 1..4096)
            require(value.identities.size in 1..256 && value.identities.all{it.length in 1..4096})
        }
    }
}
