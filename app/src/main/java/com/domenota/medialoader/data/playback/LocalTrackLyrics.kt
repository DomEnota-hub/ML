package com.domenota.medialoader.data.playback

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.ui.components.readWaveformSourceVersion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Version-bound local/imported and downloaded lyrics, no writes to media or sidecars. */
internal class LocalTrackLyrics(private val context:Context) {
    private val root=directory(context)
    private fun key(uri:Uri):String? {
        if(uri.scheme !in setOf("file","content")) return null
        val v=readWaveformSourceVersion(context,uri)
        if(!v.proven) return null
        return hash(listOf(uri.toString(),v.size.toString(),v.modified.toString()).joinToString("|"))
    }
    private fun file(key:String)=File(root,key+".json")
    private fun source(access:AudioBookmarkAdmission):Uri=Uri.parse(requireNotNull(access.ref.item.savedUri))
    private fun admitted(access:AudioBookmarkAdmission,allowed:()->Boolean):Boolean =
        allowed() && runCatching { access.requireCurrent(context) }.isSuccess
    private fun aliases(access:AudioBookmarkAdmission):Set<String> {
        val ref=access.ref
        return (ref.aliases + ref.key + ref.item.id + listOfNotNull(ref.appDownloadId) +
            PublicMediaIdentity.uriKeys(source(access).toString())).mapTo(linkedSetOf(),::hash)
    }
    private fun load(key:String,access:AudioBookmarkAdmission):TrackLyrics?=runCatching {
        val f=file(key)
        val o=readRecord(f) ?: return@runCatching null
        if(o.getInt("schema")!=2 || o.getString("key")!=key || recordAliases(o).none(aliases(access)::contains)) return@runCatching null
        val rows=o.getJSONArray("timed")
        if(rows.length()>LyricsParser.MAX_LINES) return@runCatching null
        val timed=(0 until rows.length()).map{ i->rows.getJSONArray(i).let{LyricLine(it.getLong(0),it.getString(1))}}
        if(timed.sumOf{it.text.length.toLong()}>LyricsParser.MAX_TEXT) return@runCatching null
        val origin=when(o.optString("origin","IMPORTED")){
            "IMPORTED"->LyricsOrigin.IMPORTED
            "LRCLIB"->LyricsOrigin.LRCLIB
            else->return@runCatching null
        }
        TrackLyrics(o.getString("plain"),timed,origin)
    }.getOrNull()
    private fun readable(uri:Uri):Boolean=runCatching {
        context.contentResolver.openInputStream(uri)?.use{it.read()>=0}==true
    }.getOrDefault(false)
    private fun sidecar(access:AudioBookmarkAdmission,uri:Uri):TrackLyrics?=runCatching {
        val audio=if(uri.scheme=="file") File(requireNotNull(uri.path)).canonicalFile else {
            val physical=access.ref.aliases.filter{it.startsWith("file-stat:")}.toSet()
            if(physical.isEmpty()) return@runCatching null
            access.ref.aliases.filter{it.startsWith("file:")}.map{File(it.removePrefix("file:")).canonicalFile}
                .filter{it.isFile && PublicMediaIdentity.pathKeys(it.path).any(physical::contains)}
                .distinct().singleOrNull() ?: return@runCatching null
        }
        val sibling=File(audio.parentFile,audio.nameWithoutExtension+".lrc")
        if(!sibling.isFile || sibling.canonicalFile!=sibling.absoluteFile || sibling.length()>LyricsParser.MAX_BYTES) null
        else sibling.inputStream().use(LyricsParser::read)?.let{LyricsParser.parse(it,LyricsOrigin.SIDECAR)}
    }.getOrNull()
    fun read(access:AudioBookmarkAdmission,allowed:()->Boolean):TrackLyrics? {
        if(!admitted(access,allowed)) return null
        val uri=source(access)
        val version=key(uri) ?: return null
        if(!readable(uri)) return null
        val cached=synchronized(DuplicatePrivacyGuard.lock) {
            if(admitted(access,allowed)) load(version,access) else null
        }
        if(!admitted(access,allowed)) return null
        val imported=cached?.takeIf{it.origin==LyricsOrigin.IMPORTED}
        val embedded=if(imported==null) runCatching {
            context.contentResolver.openInputStream(uri)?.use(EmbeddedLyrics::read)
        }.getOrNull() else null
        val sibling=if(imported==null && embedded?.timed?.isNotEmpty()!=true) sidecar(access,uri) else null
        val result=imported ?: embedded?.takeIf{it.timed.isNotEmpty()} ?: sibling ?: embedded ?: cached
        return result?.takeIf{admitted(access,allowed) && key(uri)==version && readable(uri)}
    }
    fun importDocument(access:AudioBookmarkAdmission,document:Uri,allowed:()->Boolean):TrackLyrics? {
        if(!admitted(access,allowed) || document.scheme!="content") return null
        val uri=source(access)
        val version=key(uri) ?: return null
        if(!readable(uri)) return null
        val lyrics=runCatching{context.contentResolver.openInputStream(document)?.use(LyricsParser::read)}
            .getOrNull()?.let{LyricsParser.parse(it,LyricsOrigin.IMPORTED)} ?: return null
        return persist(access,version,lyrics,allowed)
    }
    fun versionKey(access:AudioBookmarkAdmission,allowed:()->Boolean):String? {
        if(!admitted(access,allowed))return null
        return key(source(access))?.takeIf{admitted(access,allowed)}
    }
    /** Extract only tag/recognition metadata from an admitted public source; never a filename or URI. */
    fun networkTarget(access:AudioBookmarkAdmission,manual:Pair<String,String>?=null,allowed:()->Boolean):NetworkLyricsTarget? {
        if(!admitted(access,allowed)) return null
        val uri=source(access)
        val version=key(uri) ?: return null
        if(!readable(uri)) return null
        val row=access.ref.item
        val metadata=runCatching {
            val retriever=android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context,uri)
                LyricsTrackSignature(
                    row.recognizedTitle?.takeIf{it.isNotBlank()} ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)?.takeIf{it.isNotBlank()} ?: row.sourceAudioTitle?.takeUnless{it.equals(row.originalName,true)||it.equals(row.originalName.substringBeforeLast('.'),true)}.orEmpty(),
                    row.recognizedArtist?.takeIf{it.isNotBlank()} ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)?.takeIf{it.isNotBlank()&&it!="<unknown>"} ?: row.sourceAudioArtist.orEmpty(),
                    row.recognizedAlbum ?: retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM),
                    retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L)
            } finally { retriever.release() }
        }.getOrNull() ?: return null
        val signature=manual?.let{metadata.copy(title=it.first.trim(),artist=it.second.trim(),album=null)} ?: metadata
        return NetworkLyricsTarget(version,signature).takeIf {
            signature.eligible && networkCurrent(access,it,allowed)
        }
    }
    fun networkCurrent(access:AudioBookmarkAdmission,target:NetworkLyricsTarget,allowed:()->Boolean):Boolean {
        if(!admitted(access,allowed)) return false
        val uri=source(access)
        return key(uri)==target.version && readable(uri) && admitted(access,allowed)
    }
    fun saveNetwork(access:AudioBookmarkAdmission,target:NetworkLyricsTarget,lyrics:TrackLyrics,allowed:()->Boolean):TrackLyrics? {
        if(lyrics.origin!=LyricsOrigin.LRCLIB || !networkCurrent(access,target,allowed)) return null
        return persist(access,target.version,lyrics,allowed)
    }
    private fun persist(access:AudioBookmarkAdmission,version:String,lyrics:TrackLyrics,allowed:()->Boolean):TrackLyrics? {
        if(!admitted(access,allowed)) return null
        val uri=source(access)
        if(key(uri)!=version) return null
        val addressAliases=aliases(access)
        if(addressAliases.size !in 1..256) return null
        val o=JSONObject().put("schema",2).put("key",version).put("plain",lyrics.plain)
            .put("origin",lyrics.origin.name).put("aliases",JSONArray(addressAliases.sorted()))
            .put("timed",JSONArray().apply{lyrics.timed.forEach{put(JSONArray().put(it.atMs).put(it.text))}})
        val bytes=o.toString().toByteArray(Charsets.UTF_8)
        if(bytes.size>2*LyricsParser.MAX_BYTES) return null
        if(key(uri)!=version || !readable(uri)) return null
        val stored=synchronized(DuplicatePrivacyGuard.lock) {
            if(!admitted(access,allowed) || key(uri)!=version) return@synchronized false
            if(lyrics.origin==LyricsOrigin.LRCLIB && load(version,access)?.origin==LyricsOrigin.IMPORTED) return@synchronized false
            runCatching {
                check(root.isDirectory || root.mkdirs())
                val f=AtomicFile(file(version))
                val out=f.startWrite()
                try {
                    out.write(bytes)
                    check(admitted(access,allowed))
                    f.finishWrite(out)
                } catch(t:Throwable){f.failWrite(out);throw t}
                true
            }.getOrDefault(false)
        }
        return lyrics.takeIf { stored && admitted(access,allowed) && key(uri)==version && readable(uri) }
    }
    companion object {
        private fun directory(context:Context)=File(context.noBackupFilesDir,"track-lyrics-v1")
        private fun hash(value:String):String=MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8)).joinToString(""){ "%02x".format(it.toInt() and 255) }
        private fun readRecord(f:File):JSONObject?=runCatching {
            if(!f.isFile || f.length() !in 1..2L*LyricsParser.MAX_BYTES) null
            else JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
        }.getOrNull()
        private fun recordAliases(o:JSONObject):Set<String> {
            val a=o.getJSONArray("aliases")
            require(a.length() in 1..256)
            return (0 until a.length()).mapTo(linkedSetOf()){a.getString(it)}
                .also{ require(it.all { key->key.matches(Regex("[0-9a-f]{64}")) }) }
        }
        /** Called under the same privacy lock as public persistence. Old/corrupt
         * records cannot prove that they are unrelated, and are discarded. */
        fun clearForPrivacy(context:Context,id:String,identities:Set<String>) {
            val revoked=(identities+id).mapTo(hashSetOf(),::hash)
            val root=directory(context)
            val names=root.listFiles().orEmpty().map{it.name.removeSuffix(".bak").removeSuffix(".new")}.toSet()
            for(name in names) {
                if(!name.matches(Regex("[0-9a-f]{64}\\.json"))) continue
                val target=File(root,name)
                val keep=runCatching {
                    val o=readRecord(target) ?: return@runCatching false
                    o.getInt("schema")==2 && identities.isNotEmpty() && recordAliases(o).none(revoked::contains)
                }.getOrDefault(false)
                if(!keep) {
                    AtomicFile(target).delete()
                    File(root,name+".bak").delete()
                    File(root,name+".new").delete()
                }
            }
        }
    }
}
