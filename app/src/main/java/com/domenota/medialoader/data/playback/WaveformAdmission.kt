package com.domenota.medialoader.data.playback

import android.content.Context
import android.net.Uri
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.archive.SimpleArchiveStore
import com.domenota.medialoader.data.catalog.visibility.*
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.ExternalMediaCatalogRepository
import com.domenota.medialoader.data.storage.HistoricalRecoverySignals
import kotlinx.coroutines.flow.first

/** Reuse public visibility proof, never treat an unavailable index as an empty one. */
internal class WaveformAdmission private constructor(
    val rows:List<DownloadEntity>,private val blocked:Set<String>,private val epoch:Long,
    private val recovery:Long,private val nomedia:NomediaFolderSnapshot,
    private val archive:SelectiveArchivePrivacySnapshot,private val guard:FolderVisibilityLibraryGuard,
    private val simpleRevision:Long
) {
    private val physical=blocked.any{it.startsWith("file:")||it.startsWith("file-stat:")}
    val requiresPhysicalProof get()=physical
    fun lyricsProtectedKeys():Set<String> = blocked.filterTo(hashSetOf()){it.startsWith("file:")||it.startsWith("file-stat:")}
    private val byUri=rows.groupBy{it.savedUri}
    private val allowedKeys=hashMapOf<String,Pair<String,Set<String>>>()
    fun current(context:Context,repo:MediaRepository):Boolean =
        !HistoricalRecoverySignals.hasPendingJournal(context) && currentFast(context,repo)
    fun currentFast(context:Context,repo:MediaRepository,source:String?=null):Boolean =
        !HistoricalRecoverySignals.isRunning.value && SimpleArchiveStore.revisionCurrent(simpleRevision) &&
        HistoricalRecoverySignals.version.value==recovery &&
        DuplicatePrivacyGuard.generation(context)==epoch &&
        repo.nomediaFolders.snapshot.value==nomedia && repo.selectiveArchive.privacy.value==archive &&
        (source==null||allowedKeys[source]?.let{DuplicatePrivacyGuard.accepts(context,epoch,it.first,it.second)}==true)

    suspend fun allows(context:Context,source:String,id:String?,physicalKeys:Set<String> = emptySet()):Boolean {
        val uri=Uri.parse(source)
        val known=byUri[source].orEmpty()
        if(known.any{it.hidden||it.isPrivate||guard.appExcluded(it)})return false
        if(known.isEmpty() && (uri.scheme!="content"||uri.authority!="media"||"/audio/media/" !in source))return false
        val key=id?:known.firstOrNull()?.id?:source
        val aliases=PublicMediaIdentity.uriKeys(source)+key+"app:$key"
        if(aliases.any(blocked::contains))return false
        val proof=if(!physical)emptySet() else if(physicalKeys.any{it.startsWith("file-stat:")})physicalKeys else PublicMediaIdentity.capture(context,source)
        if(physical&&proof.none{it.startsWith("file-stat:")})return false
        val keys=aliases+proof
        val allowed=keys.none(blocked::contains)&&DuplicatePrivacyGuard.accepts(context,epoch,key,keys)
        if(allowed)allowedKeys[source]=key to keys
        return allowed
    }
    suspend fun allowsLyricsRef(context:Context,ref:com.domenota.medialoader.data.library.LibraryMediaRef):Boolean {
        val row=ref.item
        val uri=row.savedUri ?: return false
        if(row.hidden||row.isPrivate||guard.excluded(ref)||ref.aliases.any(blocked::contains))return false
        return allows(context,uri,ref.appDownloadId ?: ref.key,ref.aliases)
    }
    companion object {
        suspend fun read(context:Context,repo:MediaRepository):WaveformAdmission {
            check(!HistoricalRecoverySignals.hasPendingJournal(context))
            val simpleRevision=SimpleArchiveStore.revision()
            check(SimpleArchiveStore.revisionCurrent(simpleRevision))
            val epoch=DuplicatePrivacyGuard.generation(context)
            val recovery=HistoricalRecoverySignals.version.value
            val rows=repo.downloads.first()
            val nomedia=repo.nomediaFolders.snapshot.value
            check(!nomedia.unknown)
            val archive=repo.selectiveArchive.refreshPrivacy()
            check(archive.verified)
            val guard=FolderVisibilityLibraryGuard(FolderVisibilityReadPolicy(repo.folderVisibility.snapshot.first(),
                nomediaHiddenFolders=nomedia.hiddenFolderIds,archiveBlockedIds=archive.blockedIds,
                archiveBlockedUris=archive.blockedUris,archiveBlockedAliases=archive.blockedAliases),
                repo.catalogAssignments.first(),ExternalMediaCatalogRepository(context).visibilityEntries())
            val blocked=buildSet {
                addAll(repo.privateMediaIds.first());addAll(DuplicatePrivacyGuard.currentBlockedKeys());addAll(guard.blockedKeys())
                for(row in rows.filter{it.hidden||it.isPrivate||guard.appExcluded(it)}) {
                    add(row.id);add("app:${row.id}");row.savedUri?.let{addAll(PublicMediaIdentity.capture(context,it))}
                }
                for(row in SimpleArchiveStore(context).refresh()) {
                    add(row.catalogId);addAll(PublicMediaIdentity.capture(context,row.savedUri))
                }
            }
            return WaveformAdmission(rows,blocked,epoch,recovery,nomedia,archive,guard,simpleRevision).also{check(it.current(context,repo))}
        }
    }
}
