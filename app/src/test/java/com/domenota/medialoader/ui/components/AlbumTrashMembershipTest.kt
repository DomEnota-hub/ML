package com.domenota.medialoader.ui.components

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryMediaRef
import org.junit.Assert.*
import org.junit.Test

class AlbumTrashMembershipTest {
    private fun row(id: String) = LibraryMediaRef(id,setOf(id,"alias-$id"),
        DownloadEntity(id,"local","$id.jpg",MediaType.PHOTO,DownloadState.COMPLETED,
            "content://media/external/images/media/$id",123L,1000L),null)

    @Test fun onlyExplicitMembersRemainEligibleAfterPartialCleanup() {
        val a = row("1"); val b = row("2")
        val request = AlbumTrashRequest("folder",listOf(a,b),setOf("alias-1","2"))
        assertTrue(albumTrashMembershipSafe(request,setOf("alias-1","2"),a))
        assertTrue(albumTrashMembershipSafe(request,setOf("2"),b))
        assertFalse(albumTrashMembershipSafe(request,setOf("2"),a))
        assertFalse(albumTrashMembershipSafe(request,setOf("2","new-member"),b))
        assertFalse(albumTrashMembershipSafe(request,setOf("2"),row("3")))
    }
}
