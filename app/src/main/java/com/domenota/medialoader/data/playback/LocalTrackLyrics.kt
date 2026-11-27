package com.domenota.medialoader.data.playback

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.ui.components.readWaveformSourceVersion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Only a caller with a current public track may read/import. No writes to audio or sidecars. */
internal class LocalTrackLyrics(private val context:Context) {
    private val root=File(context.noBackupFilesDir,"track-lyrics-v1")
    private fun key(uri:Uri,epoch:Long):String? {
        if(uri.scheme !in setOf("file","content")) return null
        val v=readWaveformSourceVersion(context,uri)
        if(!v.proven) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(listOf(uri.toString(),v.size.toString(),v.modified.toString(),epoch.toString()).joinToString("|").toByteArray(Charsets.UTF_8))
            .joinToString(""){ "%02x".format(it.toInt() and 255) }
    }
    private fun file(key:String)=File(root,key+".json")
    private fun load(key:String):TrackLyrics?=runCatching {
        val f=file(key)
        if(!f.isFile || f.length() !in 1..2L*LyricsParser.MAX_BYTES) return@runCatching null
        val o=JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
        if(o.getInt("schema")!=1 || o.getString("key")!=key) return@runCatching null
        val rows=o.getJSONArray("timed")
        if(rows.length()>LyricsParser.MAX_LINES) return@runCatching null
        val timed=(0 until rows.length()).map{ i->rows.getJSONArray(i).let{LyricLine(it.getLong(0),it.getString(1))}}
        if(timed.sumOf{it.text.length.toLong()}>LyricsParser.MAX_TEXT) return@runCatching null
        TrackLyrics(o.getString("plain"),timed,LyricsOrigin.IMPORTED)
    }.getOrNull()
    fun read(uri:Uri,epoch:Long,allowed:()->Boolean):TrackLyrics? {
        if(!allowed() || uri.scheme !in setOf("file","content")) return null
        val version=key(uri,epoch)
        val imported=version?.let(::load)
        val embedded=if(imported==null) runCatching {
            context.contentResolver.openInputStream(uri)?.use(EmbeddedLyrics::read)
        }.getOrNull() else null
        val sidecar=if(imported==null && embedded?.timed?.isNotEmpty()!=true && uri.scheme=="file") runCatching {
            val audio=File(requireNotNull(uri.path)).canonicalFile
            val sibling=File(audio.parentFile,audio.nameWithoutExtension+".lrc")
            if(!sibling.isFile || sibling.canonicalFile!=sibling.absoluteFile || sibling.length()>LyricsParser.MAX_BYTES) null
            else sibling.inputStream().use(LyricsParser::read)?.let{LyricsParser.parse(it,LyricsOrigin.SIDECAR)}
        }.getOrNull() else null
        val result=imported ?: embedded?.takeIf{it.timed.isNotEmpty()} ?: sidecar ?: embedded
        return result?.takeIf{allowed() && key(uri,epoch)==version}
    }
    fun importDocument(uri:Uri,epoch:Long,document:Uri,allowed:()->Boolean):TrackLyrics? {
        if(!allowed() || document.scheme!="content") return null
        val version=key(uri,epoch) ?: return null
        val lyrics=runCatching{context.contentResolver.openInputStream(document)?.use(LyricsParser::read)}
            .getOrNull()?.let{LyricsParser.parse(it,LyricsOrigin.IMPORTED)} ?: return null
        if(!allowed() || key(uri,epoch)!=version) return null
        val o=JSONObject().put("schema",1).put("key",version).put("plain",lyrics.plain)
            .put("timed",JSONArray().apply{lyrics.timed.forEach{put(JSONArray().put(it.atMs).put(it.text))}})
        val bytes=o.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>2*LyricsParser.MAX_BYTES) return null
        return runCatching {
            check(root.isDirectory || root.mkdirs())
            val f=AtomicFile(file(version))
            val out=f.startWrite()
            try {
                out.write(bytes)
                check(allowed() && key(uri,epoch)==version)
                f.finishWrite(out)
            } catch(t:Throwable){f.failWrite(out);throw t}
            if(allowed() && key(uri,epoch)==version) lyrics else {f.delete();null}
        }.getOrNull()
    }
}
