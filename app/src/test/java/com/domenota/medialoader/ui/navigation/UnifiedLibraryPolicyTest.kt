package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.core.database.CatalogAssignmentEntity
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import com.domenota.medialoader.ui.screens.music.MusicTrackVisibility
import org.junit.Assert.*
import org.junit.Test

class UnifiedLibraryPolicyTest{
    private fun row(id:String,type:MediaType=MediaType.PHOTO)=DownloadEntity(
        id,"test","$id.dat",type,DownloadState.COMPLETED,"content://media/$id",100L,1000L)
    private fun ref(row:DownloadEntity,app:Boolean=false)=LibraryMediaRef(
        row.id,setOf(row.id,"uri:${row.savedUri}"),row,row.id.takeIf{app})
    private fun assignment(id:String,yes:Boolean=true)=CatalogAssignmentEntity(id,null,yes)
    @Test fun favoritesUseExistingAssignmentsAcrossAllTypesAndSources(){
        val photo=row("photo")
        val video=row("video",MediaType.VIDEO)
        val audio=row("audio",MediaType.AUDIO)
        val other=row("other")
        val refs=listOf(ref(photo,true),ref(video),ref(audio),ref(other),ref(audio))
        assertEquals(setOf("photo","video","audio"),
            publicFavoriteRows(refs,listOf(assignment("photo"),assignment("video"),assignment("audio")),
                emptySet(),emptySet()).map{it.id}.toSet())
        assertEquals(3,publicFavoriteRows(refs,listOf(assignment("photo"),assignment("video"),assignment("audio")),
            emptySet(),emptySet()).size)
    }
    @Test fun favoritesNeverPublishPrivateHiddenArchivedFailedOrRevokedRows(){
        val good=row("good")
        val audio=row("secret-audio",MediaType.AUDIO)
        val refs=listOf(ref(good),ref(audio),ref(row("secret").copy(isPrivate=true)),
            ref(row("hidden").copy(hidden=true)),ref(row("archive")),ref(row("uri-block")),
            ref(row("missing").copy(savedUri=null)),ref(row("failed").copy(state=DownloadState.FAILED)))
        val result=publicFavoriteRows(refs,refs.map{assignment(it.key)},
            setOf("archive","uri:content://media/uri-block"),MusicTrackVisibility.update(emptySet(),audio,true))
        assertEquals(listOf("good"),result.map{it.id})
    }
    @Test fun explicitAppUnfavoriteWinsOverStaleExternalAlias(){
        val file=row("app-id")
        val ref=LibraryMediaRef("external-id",setOf("app-id","external-id"),file,"app-id")
        assertTrue(publicFavoriteRows(listOf(ref),listOf(assignment("app-id",false),assignment("external-id")),
            emptySet(),emptySet()).isEmpty())
    }
    @Test fun mediaFilterAndDeleteConfirmationNeverExpandToAnotherTypeOrNewArrival(){
        val rows=listOf(row("photo"),row("video",MediaType.VIDEO),row("audio",MediaType.AUDIO))
        assertSame(rows,filterLibraryMedia(rows,null){it.mediaType})
        assertEquals(listOf("video"),filterLibraryMedia(rows,MediaType.VIDEO){it.mediaType}.map{it.id})
        val original=setOf("video","removed")
        assertEquals(setOf("video"),confirmedLibraryIds(original,setOf("video","new")))
        assertTrue(confirmedLibraryIds(original,setOf("photo","audio")).isEmpty())
    }
}
