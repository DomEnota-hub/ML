package com.domenota.medialoader.data.playback

import android.content.Context
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.library.AppMode
import com.domenota.medialoader.data.library.DuplicatePrivacyGuard
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.data.library.PhoneMediaLibrary
import com.domenota.medialoader.data.library.UnifiedMediaLibrary
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Rebuild a fresh admitted public catalog when starting/resuming. The persisted plan
 * contains no URI, filename, metadata, or lyrics. A changed version is never rebound. */
internal class LyricsBatchCatalog private constructor(
    private val app:Context, private val repo:MediaRepository, private val gate:WaveformAdmission,
    private val permissions:List<Boolean>, val entries:Map<String,AudioBookmarkAdmission>
) {
    fun current()=gate.current(app,repo)&&permissions==permissionSignature(app)
    fun allowed(access:AudioBookmarkAdmission):Boolean {
        val uri=access.ref.item.savedUri ?: return false
        return permissions==permissionSignature(app) && gate.currentFast(app,repo,uri) &&
            runCatching{access.requireCurrent(app)}.isSuccess
    }
    companion object {
        private fun permissionSignature(context:Context)=PhoneMediaLibrary.permissions(AppMode.MUSIC).map{
            ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED
        }
        suspend fun read(app:Context,continueWork:()->Boolean):LyricsBatchCatalog {
            val repo=MediaRepository.get(app)
            val gate=WaveformAdmission.read(app,repo)
            val epoch=DuplicatePrivacyGuard.generation(app)
            val permissions=permissionSignature(app)
            fun current()=continueWork()&&gate.current(app,repo)&&permissions==permissionSignature(app)&&epoch==DuplicatePrivacyGuard.generation(app)
            fun checkCurrent(){check(current()){"Catalog changed"}}
            val appRefs=mutableListOf<LibraryMediaRef>()
            for(row in gate.rows){
                currentCoroutineContext().ensureActive()
                if(!continueWork())error("Stopped")
                if(row.mediaType!=MediaType.AUDIO||row.state!=DownloadState.COMPLETED||row.hidden||row.isPrivate)continue
                val uri=row.savedUri ?:continue
                val aliases=PublicMediaIdentity.capture(app,uri)+row.id
                val ref=LibraryMediaRef(row.id,aliases,row,row.id)
                if(gate.allowsLyricsRef(app,ref))appRefs.add(ref)
            }
            checkCurrent()
            val phone=PhoneMediaLibrary(app).scanSnapshot(AppMode.MUSIC,protectedPhysicalKeys=gate.lyricsProtectedKeys(),requireComplete=true)
            checkCurrent()
            val phoneRefs=mutableListOf<LibraryMediaRef>()
            for(row in phone.items){
                currentCoroutineContext().ensureActive()
                if(!continueWork())error("Stopped")
                val uri=row.savedUri ?: continue
                val aliases=phone.identities[uri].orEmpty()+row.id
                val ref=LibraryMediaRef(row.id,aliases,row,null)
                if(gate.allowsLyricsRef(app,ref))phoneRefs.add(ref)
            }
            val store=LocalTrackLyrics(app)
            val entries=linkedMapOf<String,AudioBookmarkAdmission>()
            for(ref in UnifiedMediaLibrary.merge(appRefs,phoneRefs,emptySet())){
                currentCoroutineContext().ensureActive()
                if(!continueWork())error("Stopped")
                if(!gate.allowsLyricsRef(app,ref))continue
                val access=AudioBookmarkAdmission(ref,epoch)
                val uri=ref.item.savedUri ?: continue
                val key=store.versionKey(access){continueWork()&&gate.currentFast(app,repo,uri)&&permissions==permissionSignature(app)}
                if(key!=null)entries.putIfAbsent(key,access)
            }
            checkCurrent()
            return LyricsBatchCatalog(app,repo,gate,permissions,entries)
        }
    }
}
