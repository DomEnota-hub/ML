package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.*
import org.junit.Test

class PhysicalAlbumPolicyTest {
    @Test fun protectedAndUnsafeFoldersAreNotRemovable() {
        for (path in listOf("DCIM/", "DCIM/Camera/", "Pictures/Screenshots/", "Android/media/", "../Photos/", "/Pictures/Restored/", "Pictures/a/../b/"))
            assertFalse(path,PhysicalAlbumPolicy.removableSource(path))
        assertTrue(PhysicalAlbumPolicy.removableSource("Pictures/Restored/"))
        assertTrue(PhysicalAlbumPolicy.removableSource("DCIM/MediaLoader/Work/"))
    }

    @Test fun destinationMustPreserveIdentityAndDates() {
        val source = PhysicalAlbumPolicy.Snapshot("Pictures/Restored/","12","a.jpg",123,456,"external_primary")
        val moved = source.copy(path="Pictures/Work/",bucket="13")
        assertTrue(PhysicalAlbumPolicy.verifyDestination(source,moved,"Pictures/Work/"))
        assertFalse(PhysicalAlbumPolicy.verifyDestination(source,moved.copy(name="a (1).jpg"),"Pictures/Work/"))
        assertFalse(PhysicalAlbumPolicy.verifyDestination(source,moved.copy(size=124),"Pictures/Work/"))
        assertFalse(PhysicalAlbumPolicy.verifyDestination(source,moved.copy(dateAdded=457),"Pictures/Work/"))
        assertFalse(PhysicalAlbumPolicy.verifyDestination(source,moved.copy(volume="other"),"Pictures/Work/"))
    }
}
