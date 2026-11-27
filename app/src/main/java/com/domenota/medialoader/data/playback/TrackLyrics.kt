package com.domenota.medialoader.data.playback

import java.io.DataInputStream
import java.io.InputStream
import java.util.Locale

internal enum class LyricsOrigin { EMBEDDED, SIDECAR, IMPORTED }
internal data class LyricLine(val atMs: Long, val text: String)
internal data class TrackLyrics(val plain: String, val timed: List<LyricLine>, val origin: LyricsOrigin) {
    init {
        require(plain.length <= LyricsParser.MAX_TEXT && timed.size <= LyricsParser.MAX_LINES)
        require(timed.all { it.atMs >= 0 && it.text.length <= LyricsParser.MAX_TEXT })
        require(timed.zipWithNext().all { (a,b) -> a.atMs <= b.atMs })
    }
    fun active(positionMs: Long, correctionMs: Long = 0): Int {
        val position = positionMs.coerceAtLeast(0) + correctionMs.coerceIn(-60000,60000)
        var left = 0
        var right = timed.size
        while (left < right) {
            val mid = (left + right) ushr 1
            if (timed[mid].atMs <= position) left = mid + 1 else right = mid
        }
        return left - 1
    }
    fun seek(index: Int, correctionMs: Long, durationMs: Long): Long? =
        timed.getOrNull(index)?.let { (it.atMs - correctionMs.coerceIn(-60000,60000)).coerceAtLeast(0) }
            ?.takeIf { durationMs > 0 && it <= durationMs }
}

internal object LyricsParser {
    const val MAX_TEXT = 262144
    const val MAX_LINES = 5000
    const val MAX_BYTES = 1048576
    private val stamp = Regex("""\[(\d{1,3}):([0-5]\d)(?:[.:](\d{1,3}))?]""")
    private val offset = Regex("""(?i)^\[offset:([+-]?\d+)]\s*$""")
    private val meta = Regex("""(?i)^\[(ar|al|ti|au|by|re|ve|length|offset):.*]\s*$""")
    fun read(input: InputStream): String? {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0 || out.size() + n > MAX_BYTES) return null
            out.write(buffer,0,n)
        }
        val bytes = out.toByteArray()
        val charset = if(bytes.size>=2 && ((bytes[0]==0xff.toByte() && bytes[1]==0xfe.toByte()) ||
            (bytes[0]==0xfe.toByte() && bytes[1]==0xff.toByte()))) Charsets.UTF_16 else Charsets.UTF_8
        return runCatching { charset.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF") }
            .getOrNull()?.takeIf { it.length <= MAX_TEXT && '\u0000' !in it }
    }
    fun parse(raw: String, origin: LyricsOrigin): TrackLyrics? {
        if (raw.length > MAX_TEXT || '\u0000' in raw) return null
        val rows = raw.removePrefix("\uFEFF").lineSequence().take(MAX_LINES+1).toList()
        if (rows.size > MAX_LINES) return null
        val adjustment = rows.mapNotNull { offset.matchEntire(it.trim())?.groupValues?.get(1)?.toLongOrNull() }
            .lastOrNull()?.coerceIn(-60000,60000) ?: 0L
        val timed = mutableListOf<LyricLine>()
        val plain = mutableListOf<String>()
        for (row in rows) {
            if (meta.matches(row.trim())) continue
            var rest = row.trimStart()
            val times = mutableListOf<Long>()
            while (true) {
                val match = stamp.find(rest)?.takeIf { it.range.first == 0 } ?: break
                if(timed.size + times.size >= MAX_LINES) return null
                val (minutes, seconds, fraction) = match.destructured
                val ms = fraction.padEnd(3,'0').toLong()
                times.add((minutes.toLong()*60000 + seconds.toLong()*1000 + ms + adjustment).coerceAtLeast(0))
                rest = rest.substring(match.range.last+1)
            }
            val text = rest.trim()
            if (times.isEmpty()) plain.add(row.trimEnd()) else {
                times.forEach { timed.add(LyricLine(it,text)) }
                plain.add(text)
            }
        }
        val body = plain.joinToString("\n").trim()
        if (body.isBlank() || body.length > MAX_TEXT || timed.sumOf { it.text.length.toLong() } > MAX_TEXT) return null
        return TrackLyrics(body,timed.sortedBy { it.atMs },origin)
    }
}

/** Read-only bounded ID3v2.3/v2.4 subset. Unsupported layouts never mutate the source. */
internal object EmbeddedLyrics {
    private const val MAX_TAG = 4*1024*1024
    private fun n(bytes: ByteArray, at: Int, sync: Boolean): Int {
        var value = 0L
        repeat(4) {
            val b = bytes[at+it].toInt() and 255
            require(!sync || b < 128)
            value = (value shl if(sync) 7 else 8) or b.toLong()
        }
        require(value <= MAX_TAG)
        return value.toInt()
    }
    private fun charset(enc: Int) = when(enc) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> error("Unknown ID3 text encoding")
    }
    private fun terminator(bytes: ByteArray, start: Int, enc: Int): Int {
        val width = if(enc in 1..2) 2 else 1
        var pos = start
        while(pos+width <= bytes.size) {
            if(bytes[pos].toInt()==0 && (width==1 || bytes[pos+1].toInt()==0)) return pos
            pos += width
        }
        error("Missing ID3 terminator")
    }
    private fun decode(bytes: ByteArray, start: Int, end: Int, enc: Int): String =
        charset(enc).newDecoder().decode(java.nio.ByteBuffer.wrap(bytes,start,end-start)).toString()
            .removePrefix("\uFEFF").trimEnd('\u0000')
    private fun frame(id: String, bytes: ByteArray): TrackLyrics? = runCatching {
        if(bytes.size < 5 || bytes.size > LyricsParser.MAX_BYTES) return@runCatching null
        val enc = bytes[0].toInt() and 255
        val width = if(enc in 1..2) 2 else 1
        if(id=="USLT") {
            val start = terminator(bytes,4,enc)+width
            return@runCatching LyricsParser.parse(decode(bytes,start,bytes.size,enc),LyricsOrigin.EMBEDDED)
        }
        if(id!="SYLT" || bytes.size < 7 || bytes[4].toInt()!=2 || bytes[5].toInt()!=1) return@runCatching null
        var pos = terminator(bytes,6,enc)+width
        val rows = mutableListOf<LyricLine>()
        while(pos < bytes.size) {
            val end = terminator(bytes,pos,enc)
            val time = end+width
            require(time+4 <= bytes.size && rows.size < LyricsParser.MAX_LINES)
            val ms = java.nio.ByteBuffer.wrap(bytes,time,4).int.toLong() and 0xffffffffL
            rows.add(LyricLine(ms,decode(bytes,pos,end,enc)))
            pos = time+4
        }
        val body = rows.joinToString("\n"){it.text}
        if(body.isBlank() || body.length>LyricsParser.MAX_TEXT) null
        else TrackLyrics(body,rows.sortedBy{it.atMs},LyricsOrigin.EMBEDDED)
    }.getOrNull()
    fun read(input: InputStream): TrackLyrics? = runCatching {
        val stream = DataInputStream(input)
        val header = ByteArray(10).also(stream::readFully)
        val version = header[3].toInt()
        if(String(header,0,3,Charsets.US_ASCII)!="ID3" || version !in 3..4 || header[4].toInt()!=0 || header[5].toInt()!=0) return@runCatching null
        val tag = ByteArray(n(header,6,true)).also(stream::readFully)
        var pos = 0
        var plain: TrackLyrics? = null
        while(pos+10<=tag.size && tag[pos].toInt()!=0) {
            val id = String(tag,pos,4,Charsets.US_ASCII)
            if(!id.matches(Regex("[A-Z0-9]{4}"))) return@runCatching null
            val size = n(tag,pos+4,version==4)
            if(size<=0 || pos+10+size>tag.size) return@runCatching null
            if(id in setOf("USLT","SYLT") && tag[pos+8].toInt()==0 && tag[pos+9].toInt()==0) {
                val result = frame(id,tag.copyOfRange(pos+10,pos+10+size))
                if(result?.timed?.isNotEmpty()==true) return@runCatching result
                if(plain==null) plain=result
            }
            pos+=10+size
        }
        plain
    }.getOrNull()
}

internal data class LyricsTrackSignature(val title:String,val artist:String,val album:String?,val durationMs:Long) {
    val eligible get() = title.isNotBlank() && artist.isNotBlank() && title.length<=512 && artist.length<=512 &&
        (album?.length ?: 0)<=512 && durationMs in 1000..86400000
}
internal data class LyricsCandidate(val id:Long,val signature:LyricsTrackSignature,val instrumental:Boolean=false)
internal object LyricsMatch {
    private fun normalized(s:String) = java.text.Normalizer.normalize(s,java.text.Normalizer.Form.NFKC)
        .trim().replace(Regex("\\s+")," ").lowercase(Locale.ROOT)
    fun exact(track:LyricsTrackSignature,candidates:List<LyricsCandidate>):LyricsCandidate? {
        if(!track.eligible || candidates.size>100 || candidates.groupBy{it.id}.values.any{it.distinct().size>1}) return null
        val album=track.album?.takeIf{it.isNotBlank()} ?: return null
        return candidates.filter { c ->
            val s=c.signature
            c.id>0 && s.eligible && normalized(track.title)==normalized(s.title) &&
                normalized(track.artist)==normalized(s.artist) &&
                normalized(album)==normalized(s.album.orEmpty()) &&
                kotlin.math.abs(track.durationMs-s.durationMs)<=2000
        }.distinctBy{it.id}.singleOrNull()
    }
}
