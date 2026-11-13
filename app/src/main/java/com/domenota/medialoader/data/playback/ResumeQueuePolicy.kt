package com.domenota.medialoader.data.playback

import java.security.MessageDigest

/** Only public physical identity keys (which may contain locators); no copied title or audio data.
 * Session state is separate from listening counts and cannot credit a play. */
internal data class ResumeQueueSession(val id:String,val playlistId:String?,val entries:List<Set<String>>,
    val index:Int,val positionMs:Long,val durationMs:Long,val shuffle:Boolean,val repeat:Int,val speed:Float,val updatedAt:Long) {
    init {
        require(id.isNotBlank() && entries.isNotEmpty() && entries.size<=ResumeQueuePolicy.MAX_QUEUE &&
            entries.all { it.isNotEmpty() } && index in entries.indices && positionMs>=0L &&
            repeat in 0..2 && speed.isFinite() && speed in .25f..4f)
    }
}
internal data class ResolvedResumeQueue(val session:ResumeQueueSession,val ids:List<String?>) {
    val currentId: String? get() = ids.getOrNull(session.index)
    val missing: Int get() = ids.count {it==null}
    val availableIds: List<String> get() = ids.filterNotNull()
    val currentIndex: Int get() = ids.take(session.index).count {it!=null}
}

internal object ResumeQueuePolicy {
    const val MAX_SESSIONS=8
    const val MAX_QUEUE=5000
    fun contextId(playlistId:String?,entries:List<Set<String>>):String? {
        if(entries.isEmpty()||entries.size>MAX_QUEUE||entries.any{it.isEmpty()}) return null
        if(!playlistId.isNullOrBlank()) return "playlist:$playlistId"
        fun preferred(keys:Set<String>)=keys.sortedWith(compareBy<String>{ when {
            it.startsWith("app:")->0;it.startsWith("file-stat:")->1;else->2
        }}.thenBy {it}).first()
        val digest=MessageDigest.getInstance("SHA-256")
        entries.forEach { keys ->
            val bytes=preferred(keys).toByteArray(Charsets.UTF_8)
            digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array());digest.update(bytes)
        }
        return "queue:"+digest.digest().joinToString(""){"%02x".format(it.toInt() and 0xff)}
    }
    fun keep(sessions:List<ResumeQueueSession>,snapshot:ResumeQueueSession):List<ResumeQueueSession> =
        (listOf(snapshot)+sessions.filterNot{it.id==snapshot.id}).take(MAX_SESSIONS)

    /** Resolve both the physical aliases and known listening-history track ID.
     * A conflicting/ambiguous match is unavailable, never a guessed replacement.
     * Build an index once rather than scanning hundreds of tracks per queue entry. */
    fun resolve(session:ResumeQueueSession,candidates:List<SmartTrackCandidate>,aliases:Map<String,String>):ResolvedResumeQueue {
        val rows=SmartMusicPolicy.resolve(candidates,aliases)
        val keyIndex=rows.flatMap { (row,_)->row.keys.map {it to row.rowId} }.groupBy({it.first},{it.second})
        val trackIndex=rows.mapNotNull { (row,track)->track?.let{it to row.rowId} }.groupBy({it.first},{it.second})
        val seen=hashSetOf<String>()
        val ids=session.entries.map { keys ->
            val known=keys.mapNotNull(aliases::get).distinct()
            if(known.size>1) null else {
                val matches=(keys.flatMap{keyIndex[it].orEmpty()}+known.flatMap{trackIndex[it].orEmpty()}).distinct()
                matches.singleOrNull()?.takeIf(seen::add)
            }
        }
        return ResolvedResumeQueue(session,ids)
    }
    fun clampedPosition(session:ResumeQueueSession,liveDuration:Long?):Long =
        session.positionMs.coerceAtMost(liveDuration?.takeIf{it>0}?.minus(1) ?: Long.MAX_VALUE)
}
