package com.domenota.medialoader.data.library

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Finished aligned pair, not PCM. Cached per ordered pair of verified public
 * media, stamp, SHA, privacy epoch and grid. Entries are invalidated by a
 * format/algorithm version bump or by a privacy revocation.
 *
 * MUST increment ALGORITHM_VERSION if alignment, complete-PCM refinement or
 * short-zone acceptance changes. A valid disk hit does not infer deletion.
 */
internal object MusicPairResultCache {
    private const val MAGIC = 0x4D4C5032 // MLP2
    private const val ALGORITHM_VERSION = 1
    private const val MAX_BYTES = 256_000L
    private const val MAX_ENTRIES = 24
    private const val MAX_COLUMNS = 3600

    private fun directory(context: Context) =
        File(context.noBackupFilesDir, "duplicate-comparison-pairs-v2")
    private fun digest(bytes:ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
    private fun name(first:ComparisonFrameKey, second:ComparisonFrameKey):String {
        val input="MLPair/$ALGORITHM_VERSION/${first.digest()}/${second.digest()}"
        return digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
    private fun file(context:Context,first:ComparisonFrameKey,second:ComparisonFrameKey) =
        AtomicFile(File(directory(context),name(first,second)+".pair"))

    /** Verify every serialized index before it can reach UI or navigation. */
    internal fun valid(result:AlignedAudioComparison):Boolean {
        if(result.stepMs !in listOf(256L,512L,1024L) ||
            result.first.size !in 1..1800 || result.second.size !in 1..1800 ||
            result.columns.size !in 1..MAX_COLUMNS || result.evaluatedCells<0) return false
        if(result.shortCandidates.isNotEmpty())return false // only finished comparisons
        if(result.columns.any { c ->
            (c.first==null&&c.second==null) ||
                (c.first!=null&&c.first !in result.first.indices) ||
                (c.second!=null&&c.second !in result.second.indices)
        })return false
        val zones=result.zones
        if(zones!=zones.distinct().sortedBy{it.start} ||
            zones.any { it.start<0 || it.endExclusive>result.columns.size || it.start>=it.endExclusive })return false
        if(result.confirmedShortZones.any{it !in zones})return false
        return true
    }

    fun read(context: Context, first: ComparisonFrameKey,second: ComparisonFrameKey,
        a:List<ComparisonFrame>,b:List<ComparisonFrame>):AlignedAudioComparison? {
        if(first.epoch!=second.epoch || first.stepMs!=second.stepMs) return null
        val disk=file(context,first,second)
        val path=disk.baseFile
        if(!path.isFile)return null
        val parsed=runCatching {
            val length=path.length()
            require(length in 42..MAX_BYTES)
            val encoded=disk.openRead().use{it.readBytes()}
            require(encoded.size.toLong()==length)
            val checksum=encoded.copyOfRange(0,32)
            val payload=encoded.copyOfRange(32,encoded.size)
            require(MessageDigest.isEqual(checksum,digest(payload)))
            DataInputStream(ByteArrayInputStream(payload)).use{input->
                require(input.readInt()==MAGIC)
                require(input.readInt()==ALGORITHM_VERSION)
                val step=input.readLong()
                require(step==first.stepMs)
                val firstCount=input.readInt()
                val secondCount=input.readInt()
                require(firstCount==a.size && secondCount==b.size)
                val evaluated=input.readLong()
                val columns=List(input.readInt().also{require(it in 1..MAX_COLUMNS)}){
                    val i=input.readInt()
                    val j=input.readInt()
                    ComparisonColumn(i.takeIf{it>=0},j.takeIf{it>=0},input.readBoolean())
                }
                fun zones():List<ComparisonZone> = List(input.readInt().also{require(it in 0..MAX_COLUMNS)}){
                    ComparisonZone(input.readInt(),input.readInt())
                }
                val result=AlignedAudioComparison(step,a,b,columns,zones(),evaluated,
                    emptyList(),zones().toSet())
                require(input.read()==-1 && valid(result))
                result
            }
        }.getOrNull()
        if(parsed==null)path.delete() else path.setLastModified(System.currentTimeMillis())
        return parsed
    }

    fun write(context:Context,first:ComparisonFrameKey,second:ComparisonFrameKey,
        result:AlignedAudioComparison) {
        if(first.epoch!=second.epoch || first.stepMs!=second.stepMs ||
            first.stepMs!=result.stepMs || !valid(result))return
        runCatching {
            val payload=ByteArrayOutputStream().also{buffer->
                DataOutputStream(buffer).use{out->
                    out.writeInt(MAGIC);out.writeInt(ALGORITHM_VERSION)
                    out.writeLong(result.stepMs)
                    out.writeInt(result.first.size);out.writeInt(result.second.size)
                    out.writeLong(result.evaluatedCells)
                    out.writeInt(result.columns.size)
                    result.columns.forEach{c->
                        out.writeInt(c.first?:-1);out.writeInt(c.second?:-1)
                        out.writeBoolean(c.different)
                    }
                    fun zones(z:Collection<ComparisonZone>){
                        out.writeInt(z.size)
                        z.forEach{out.writeInt(it.start);out.writeInt(it.endExclusive)}
                    }
                    zones(result.zones)
                    zones(result.confirmedShortZones.sortedBy{it.start})
                }
            }.toByteArray()
            require(payload.size+32<=MAX_BYTES)
            val dir=directory(context)
            require(dir.isDirectory||dir.mkdirs())
            val disk=file(context,first,second)
            val output=disk.startWrite()
            try{
                output.write(digest(payload));output.write(payload)
                disk.finishWrite(output)
            }catch(t:Throwable){disk.failWrite(output);throw t}
            val entries=dir.listFiles().orEmpty().filter{it.isFile&&it.name.endsWith(".pair")}
                .sortedByDescending{it.lastModified()}
            entries.drop(MAX_ENTRIES).forEach{it.delete()}
        }
    }

    /** Must be called under DuplicatePrivacyGuard.lock. */
    fun clearForPrivacy(context:Context) {
        check(!directory(context).exists()||directory(context).deleteRecursively()) {
            "Не удалось очистить кэш результатов музыкальных пар"
        }
    }
}
