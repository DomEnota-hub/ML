package com.domenota.medialoader.data.recognition

import com.domenota.medialoader.core.recognition.MusicRecognitionResult
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/** Conservative direct ID3v2.3/v2.4 editor. Unsupported layouts go through the
 * explicit remux fallback; no input bytes are ever modified here. MPEG frames
 * (including Xing/LAME gapless metadata) are copied verbatim. */
internal object DirectMp3Tags {
    private const val MAX_TAG = 4 * 1024 * 1024
    private data class Frame(val id: String, val data: ByteArray)
    private fun unsigned(b: Byte) = b.toInt() and 255
    private fun size(bytes: ByteArray, offset: Int, sync: Boolean): Int {
        var n = 0L
        repeat(4) { i ->
            val value = unsigned(bytes[offset + i])
            require(!sync || value < 128)
            n = (n shl if (sync) 7 else 8) or value.toLong()
        }
        require(n <= MAX_TAG)
        return n.toInt()
    }
    private fun length(n: Int, sync: Boolean): ByteArray {
        val bits = if (sync) 7 else 8
        return ByteArray(4) { i -> ((n ushr ((3-i)*bits)) and ((1 shl bits)-1)).toByte() }
    }
    private fun text(value: String): ByteArray = byteArrayOf(1) + value.toByteArray(Charsets.UTF_16)
    private fun artwork(file: File): ByteArray {
        require(file.length() in 1..(10L*1024*1024))
        val bytes = file.readBytes()
        val mime = when {
            bytes.size >= 3 && unsigned(bytes[0]) == 255 && unsigned(bytes[1]) == 216 && unsigned(bytes[2]) == 255 -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0,8).contentEquals(byteArrayOf(-119,80,78,71,13,10,26,10)) -> "image/png"
            else -> throw IllegalArgumentException("Unsupported artwork encoding")
        }
        return byteArrayOf(0) + mime.toByteArray(Charsets.US_ASCII) + byteArrayOf(0,3,0) + bytes
    }
    /** Check two consecutive Layer III frames, not just the .mp3 extension or ID3 magic. */
    private fun frameLength(file: RandomAccessFile, at: Long): Int {
        require(at >= 0 && at + 4 <= file.length())
        file.seek(at)
        val header = file.readInt()
        require(header ushr 21 == 0x7ff && (header ushr 17 and 3) == 1)
        val version = header ushr 19 and 3
        require(version != 1)
        val rateIndex = header ushr 10 and 3
        val bitrateIndex = header ushr 12 and 15
        require(rateIndex < 3 && bitrateIndex in 1..14)
        val bitrate = (if (version == 3) intArrayOf(0,32,40,48,56,64,80,96,112,128,160,192,224,256,320)
            else intArrayOf(0,8,16,24,32,40,48,56,64,80,96,112,128,144,160))[bitrateIndex]
        val rate = intArrayOf(44100,48000,32000)[rateIndex] / when(version) { 3 -> 1; 2 -> 2; else -> 4 }
        return (if (version == 3) 144000 else 72000) * bitrate / rate + (header ushr 9 and 1)
    }
    fun write(input: File, output: File, result: MusicRecognitionResult, cover: File?,
        preserveExistingArtwork: Boolean, checkActive: () -> Unit = {}) {
        require(input.canonicalFile != output.canonicalFile)
        RandomAccessFile(input,"r").use { source ->
            require(source.length() >= 10)
            val header = ByteArray(10).also(source::readFully)
            val tagged = String(header,0,3,Charsets.US_ASCII) == "ID3"
            val version = if (tagged) unsigned(header[3]) else 3
            val frames = mutableListOf<Frame>()
            val audioStart = if (tagged) {
                // Extended header, unsynchronization, footer, compressed/encrypted
                // frames and v2.2 are delegated rather than silently discarding tags.
                require(version in 3..4 && header[4].toInt() == 0 && header[5].toInt() == 0)
                val bytes = ByteArray(size(header,6,true)).also(source::readFully)
                var offset = 0
                while (offset < bytes.size) {
                    if (bytes[offset].toInt() == 0) { require((offset until bytes.size).all { bytes[it].toInt() == 0 }); break }
                    require(offset + 10 <= bytes.size)
                    val id = String(bytes,offset,4,Charsets.US_ASCII)
                    require(id.matches(Regex("[A-Z0-9]{4}")))
                    val n = size(bytes,offset+4,version==4)
                    require(n > 0 && offset + 10 + n <= bytes.size && bytes[offset+8].toInt()==0 && bytes[offset+9].toInt()==0)
                    frames += Frame(id,bytes.copyOfRange(offset+10,offset+10+n))
                    offset += 10+n
                }
                10L + bytes.size
            } else 0L
            val first = frameLength(source,audioStart)
            val second = frameLength(source,audioStart+first)
            require(audioStart+first+second <= source.length())
            val replacing = setOf("TIT2","TPE1") + (if (!result.album.isNullOrBlank()) setOf("TALB") else emptySet())
            val kept = frames.filterNot { it.id in replacing || (it.id=="APIC" && (cover!=null || !preserveExistingArtwork)) }.toMutableList()
            kept += Frame("TIT2",text(result.title)); kept += Frame("TPE1",text(result.artist))
            result.album?.takeIf { it.isNotBlank() }?.let { kept += Frame("TALB",text(it)) }
            cover?.let { kept += Frame("APIC",artwork(it)) }
            val body = ByteArrayOutputStream().apply {
                for (frame in kept) {
                    require(size().toLong()+10+frame.data.size <= MAX_TAG)
                    write(frame.id.toByteArray(Charsets.US_ASCII)); write(length(frame.data.size,version==4)); write(byteArrayOf(0,0)); write(frame.data)
                }
            }.toByteArray()
            // ID3v1 fields must not contradict the new v2 values; preserve its
            // other fields (year, comment, track and genre) and the audio tail.
            var end = source.length()
            val legacy = if (end-audioStart >= 128) {
                source.seek(end-128)
                ByteArray(128).also(source::readFully).takeIf { String(it,0,3,Charsets.US_ASCII)=="TAG" }
            } else null
            if (legacy != null) end -= 128
            fun updateLegacy(offset: Int, value: String) {
                legacy ?: return
                val bytes = if (Charsets.ISO_8859_1.newEncoder().canEncode(value)) value.toByteArray(Charsets.ISO_8859_1).take(30).toByteArray() else byteArrayOf()
                legacy.fill(0,offset,offset+30); bytes.copyInto(legacy,offset)
            }
            updateLegacy(3,result.title); updateLegacy(33,result.artist)
            result.album?.takeIf { it.isNotBlank() }?.let { updateLegacy(63,it) }
            output.outputStream().buffered().use { out ->
                out.write(byteArrayOf(73,68,51,version.toByte(),0,0)); out.write(length(body.size,true)); out.write(body)
                source.seek(audioStart)
                val buffer = ByteArray(64*1024)
                var remaining = end-audioStart
                while (remaining>0) {
                    checkActive()
                    val n = minOf(buffer.size.toLong(),remaining).toInt()
                    source.readFully(buffer,0,n); out.write(buffer,0,n); remaining -= n
                }
                legacy?.let(out::write)
            }
        }
    }
}
