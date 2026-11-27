package com.domenota.medialoader.data.playback

import android.os.SystemClock
import com.domenota.medialoader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayOutputStream
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection
import kotlin.math.roundToLong
import kotlin.random.Random

internal data class NetworkLyricsTarget(val version:String,val signature:LyricsTrackSignature)
internal data class NetworkLyricsHit(val candidate:LyricsCandidate,val lyrics:TrackLyrics?)
internal sealed interface LyricsLookup {
    data class Matches(val hits:List<NetworkLyricsHit>):LyricsLookup
    data class RetryLater(val seconds:Long):LyricsLookup
    data object Unavailable:LyricsLookup
    data object Revoked:LyricsLookup
}
internal interface LyricsProvider {
    val name:String
    suspend fun search(signature:LyricsTrackSignature,allowed:()->Boolean):LyricsLookup
}
internal data class LyricsHttpResponse(val status:Int,val contentType:String?,val retryAfter:String?,val body:String?)
internal object LrclibProtocol {
    const val MAX_RESPONSE=4*1024*1024
    fun url(signature:LyricsTrackSignature):String {
        require(signature.eligible)
        fun encode(value:String)=URLEncoder.encode(value,"UTF-8")
        return "https://lrclib.net/api/search?track_name="+encode(signature.title)+"&artist_name="+encode(signature.artist)
    }
    fun parse(body:String):List<NetworkLyricsHit> {
        require(body.length<=MAX_RESPONSE)
        val rows=JSONArray(body)
        require(rows.length()<=100)
        val hits=(0 until rows.length()).map { i->
            val row=rows.getJSONObject(i)
            val id=row.getLong("id")
            val duration=row.getDouble("duration")
            require(id>0 && duration.isFinite() && duration in 1.0..86400.0)
            val signature=LyricsTrackSignature(row.getString("trackName"),row.getString("artistName"),
                row.optString("albumName").takeIf{it.isNotBlank() && it!="null"},(duration*1000).roundToLong())
            require(signature.eligible)
            val instrumental=row.getBoolean("instrumental")
            fun text(key:String)=if(row.isNull(key)) null else row.getString(key).takeIf{it.length<=LyricsParser.MAX_TEXT}
            val synced=if(instrumental)null else text("syncedLyrics")?.let{LyricsParser.parse(it,LyricsOrigin.LRCLIB)}?.takeIf{it.timed.isNotEmpty()}
            val plain=if(instrumental)null else text("plainLyrics")?.let{LyricsParser.parse(it,LyricsOrigin.LRCLIB)}
            NetworkLyricsHit(LyricsCandidate(id,signature,instrumental),synced ?: plain)
        }
        require(hits.groupBy{it.candidate.id}.values.none{it.distinct().size>1})
        return hits.distinctBy{it.candidate.id}
    }
    fun automatic(signature:LyricsTrackSignature,hits:List<NetworkLyricsHit>):NetworkLyricsHit? {
        val match=LyricsMatch.exact(signature,hits.map{it.candidate}) ?: return null
        return hits.singleOrNull{it.candidate==match}
    }
    fun retrySeconds(value:String?):Long=value?.trim()?.toLongOrNull()?.takeIf{it>=0} ?: 60L
}
internal class LrclibLyricsProvider(
    private val transport:(String,()->Boolean)->LyricsHttpResponse = ::request,
    private val clock:()->Long=SystemClock::elapsedRealtime,
    private val pause:suspend (Long)->Unit={delay(it)},
):LyricsProvider {
    override val name="LRCLIB"
    private val mutex=Mutex()
    private var next=0L
    private var blockedUntil=0L
    override suspend fun search(signature:LyricsTrackSignature,allowed:()->Boolean):LyricsLookup=withContext(Dispatchers.IO) {
        mutex.withLock {
            if(!allowed()) return@withLock LyricsLookup.Revoked
            if(!signature.eligible) return@withLock LyricsLookup.Unavailable
            val wait=blockedUntil-clock()
            if(wait>0) return@withLock LyricsLookup.RetryLater(wait/1000+1)
            val spacing=next-clock()
            if(spacing>0) pause(spacing)
            if(!allowed()) return@withLock LyricsLookup.Revoked
            try {
                val response=transport(LrclibProtocol.url(signature),allowed)
                if(response.status==429 || response.status==503) {
                    val seconds=LrclibProtocol.retrySeconds(response.retryAfter).coerceAtLeast(1)
                    val ms=seconds.coerceAtMost(Long.MAX_VALUE/2000)*1000
                    val jitter=Random.nextLong(0,(ms/5).coerceAtLeast(1))
                    blockedUntil=(clock()+ms+jitter).coerceAtLeast(0)
                    return@withLock LyricsLookup.RetryLater(seconds)
                }
                if(!allowed()) return@withLock LyricsLookup.Revoked
                when {
                    response.status==404 -> LyricsLookup.Matches(emptyList())
                    response.status==200 && response.contentType?.substringBefore(';')?.trim()?.equals("application/json",true)==true ->
                        LyricsLookup.Matches(LrclibProtocol.parse(requireNotNull(response.body)))
                    else->LyricsLookup.Unavailable
                }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(_:Exception){if(allowed()) LyricsLookup.Unavailable else LyricsLookup.Revoked}
            finally {next=clock()+500L}
        }
    }
    companion object {
        val shared:LrclibLyricsProvider by lazy { LrclibLyricsProvider() }
        private fun request(url:String,allowed:()->Boolean):LyricsHttpResponse {
            check(allowed())
            val connection=URL(url).openConnection() as HttpsURLConnection
            try {
                connection.instanceFollowRedirects=false
                connection.requestMethod="GET"
                connection.connectTimeout=10000
                connection.readTimeout=15000
                connection.setRequestProperty("User-Agent","MediaLoader/"+BuildConfig.VERSION_NAME+" (https://github.com/DomEnota-hub/ML)")
                connection.setRequestProperty("Accept","application/json")
                check(allowed())
                val status=connection.responseCode
                val type=connection.contentType
                val retry=connection.getHeaderField("Retry-After")
                val body=if(status==200 && type?.substringBefore(';')?.trim()?.equals("application/json",true)==true) {
                    connection.inputStream.use { input ->
                        val out=ByteArrayOutputStream()
                        val buffer=ByteArray(8192)
                        while(true) {
                            check(allowed())
                            val n=input.read(buffer)
                            if(n<0)break
                            require(n>0 && out.size()+n<=LrclibProtocol.MAX_RESPONSE)
                            out.write(buffer,0,n)
                        }
                        Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
                    }
                } else null
                return LyricsHttpResponse(status,type,retry,body)
            } finally { connection.disconnect() }
        }
    }
}
