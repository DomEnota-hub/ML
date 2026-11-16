package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.library.*
import org.junit.Assert.*
import org.junit.Test

class LibraryDisplayInventoryTest {
    private fun row(id: String, type: MediaType = MediaType.PHOTO) = DownloadEntity(id, "device", id,
        type, DownloadState.COMPLETED, "content://media/$id", 1, 0)
    @Test fun derivedListsPreserveMembershipOrderAndStableIdentity() {
        val a = LibraryMediaRef("a", setOf("a"), row("a"), "a")
        val b = LibraryMediaRef("b", setOf("b", "alias"), row("b", MediaType.AUDIO), null, "bucket", null)
        val c = LibraryMediaRef("c", setOf("c"), row("c", MediaType.AUDIO), null)
        val inventory = LibraryDisplayInventory.build(listOf(a,b,c), listOf(ExternalMediaEntry(b,"f",false)))
        assertEquals(listOf("a","b","c"), inventory.items.map { it.id })
        assertEquals(listOf("b","c"), inventory.audio.map { it.id })
        assertEquals(listOf("a","b"), inventory.appItems.map { it.id })
        assertEquals(listOf("b"), inventory.appAudio.map { it.id })
        assertEquals("Без альбома", inventory.deviceAlbums.getValue("b").name)
        assertSame(inventory.items, inventory.items)
        assertSame(inventory.deviceAlbums, inventory.deviceAlbums)
        assertSame(LibraryDisplayInventory.EMPTY, LibraryDisplayInventory.build(emptyList(), emptyList()))
    }
    @Test fun indexedAndSessionRowsKeepHistoryAliasesAndRejectBlockedAndPrivateRows() {
        val a = row("app:a")
        val ref = LibraryMediaRef(a.id, setOf("phone:image:7", "app:a"), a, a.id)
        val blocked = row("blocked")
        val hidden = LibraryMediaRef(blocked.id, setOf("phone:image:9"), blocked, null)
        val indexed = PublicLibraryRows.indexed(listOf(a, row("private").copy(isPrivate=true)), listOf(ref,hidden)) {
            it.id !in setOf("blocked", "phone:image:9")
        }
        assertEquals(listOf("app:a", "phone:image:7"), indexed.map { it.id })
        assertSame(a, indexed[0])
        assertSame(indexed, PublicLibraryRows.mergeSession(indexed, emptyList()) { true })
        assertSame(indexed, PublicLibraryRows.mergeSession(indexed, listOf(a,blocked)) { it.id != "blocked" })
        val session = row("audio",MediaType.AUDIO)
        assertEquals(indexed + session, PublicLibraryRows.mergeSession(indexed,listOf(session,session,blocked,row("secret").copy(isPrivate=true))) { it.id != "blocked" })
    }
}
