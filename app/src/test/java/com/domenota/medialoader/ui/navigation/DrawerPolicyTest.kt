package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.data.library.AppMode
import org.junit.Assert.*
import org.junit.Test

class DrawerPolicyTest{
    @Test fun onlyMainRoutesAndActualScreenAdmissionCanOpen(){
        for(mode in AppMode.entries){
            for(route in listOf("home","downloads","simple-archive",FAVORITES_ROUTE,"private","collections","music-duplicates") + (if(mode==AppMode.MUSIC)listOf("settings") else emptyList())){
                assertTrue(drawerAllowed(route,mode,true,false))
                assertFalse(drawerAllowed(route,mode,false,false))
                assertFalse(drawerAllowed(route,mode,true,true))
            }
            for(route in listOf(null,MUSIC_SEARCH_ROUTE,"trash","archive",
                "visual-duplicates","viewer/{downloadId}","video-editor/{downloadId}",
                "photo-editor/{downloadId}","audio-editor/{downloadId}","batch-photo-editor","equalizer",
                "browser/{source}","preview","bulk","playlist-add/{playlistId}","unknown") + (if(mode!=AppMode.MUSIC)listOf("settings") else emptyList())){
                assertFalse(drawerAllowed(route,mode,true,false))
            }
        }
    }
    @Test fun privateDrawerContextNeverAdmitsLockedOrPrivateViewerRoutes(){
        assertTrue(drawerPrivateContextAllowed("private",true,true))
        assertFalse(drawerPrivateContextAllowed("private",true,false))
        assertFalse(drawerPrivateContextAllowed("private",false,true))
        for(route in listOf("home","viewer/{downloadId}","vault","photo-editor/{downloadId}","settings"))
            assertFalse(drawerPrivateContextAllowed(route,true,true))
        assertTrue(drawerPrivateContextAllowed("collections",false,false))
    }
    @Test fun activeEntryUsesRouteAndModeNotPlayback(){
        assertEquals(DrawerEntry.HOME,drawerActiveEntry("home",AppMode.DOWNLOADER))
        assertEquals(DrawerEntry.GALLERY,drawerActiveEntry("home",AppMode.GALLERY))
        assertEquals(DrawerEntry.MUSIC,drawerActiveEntry("home",AppMode.MUSIC))
        assertEquals(DrawerEntry.DOWNLOADS,drawerActiveEntry("downloads",AppMode.MUSIC))
        assertEquals(DrawerEntry.ARCHIVE,drawerActiveEntry("simple-archive",AppMode.GALLERY))
        assertEquals(DrawerEntry.FAVORITES,drawerActiveEntry(FAVORITES_ROUTE,AppMode.MUSIC))
        assertEquals(DrawerEntry.PRIVATE,drawerActiveEntry("private",AppMode.GALLERY))
        assertEquals(DrawerEntry.COLLECTIONS,drawerActiveEntry("collections",AppMode.GALLERY))
        assertEquals(DrawerEntry.MUSIC,drawerActiveEntry("music-duplicates",AppMode.MUSIC))
        assertEquals(DrawerEntry.SETTINGS,drawerActiveEntry("settings",AppMode.MUSIC))
        assertNull(drawerActiveEntry(MUSIC_SEARCH_ROUTE,AppMode.MUSIC))
    }
    @Test fun edgeStartsAfterSystemBackInsetAndNeverMidContent(){
        assertFalse(DrawerGesturePolicy.canStart(8f,0f,320f,24f,24f))
        assertTrue(DrawerGesturePolicy.canStart(24f,0f,320f,24f,24f))
        assertTrue(DrawerGesturePolicy.canStart(48f,0f,320f,24f,24f))
        assertFalse(DrawerGesturePolicy.canStart(49f,0f,320f,24f,24f))
        assertFalse(DrawerGesturePolicy.canStart(150f,0f,320f,24f,24f))
        assertTrue(DrawerGesturePolicy.canStart(150f,1f,320f,24f,24f))
        assertFalse(DrawerGesturePolicy.canStart(321f,1f,320f,24f,24f))
    }
    @Test fun verticalWrongDirectionAndSmallDragDoNotCapture(){
        assertFalse(DrawerGesturePolicy.acceptHorizontal(5f,1f,8f,true))
        assertTrue(DrawerGesturePolicy.acceptHorizontal(50f,3f,8f,true))
        assertFalse(DrawerGesturePolicy.acceptHorizontal(-50f,3f,8f,true))
        assertTrue(DrawerGesturePolicy.rejectDirection(-50f,3f,8f,true))
        assertTrue(DrawerGesturePolicy.rejectDirection(3f,50f,8f,true))
        assertFalse(DrawerGesturePolicy.acceptHorizontal(20f,20f,8f,true))
        assertTrue(DrawerGesturePolicy.acceptHorizontal(-50f,3f,8f,false))
        assertTrue(DrawerGesturePolicy.rejectDirection(50f,3f,8f,false))
    }
    @Test fun dragFollowsFingerClampsAndSettlesInBothDirections(){
        assertEquals(.5f,DrawerGesturePolicy.fraction(0f,160f,320f),0f)
        assertEquals(.5f,DrawerGesturePolicy.fraction(1f,-160f,320f),0f)
        assertEquals(1f,DrawerGesturePolicy.fraction(0f,500f,320f),0f)
        assertEquals(0f,DrawerGesturePolicy.fraction(1f,-500f,320f),0f)
        assertFalse(DrawerGesturePolicy.settleOpen(.34f,true))
        assertTrue(DrawerGesturePolicy.settleOpen(.35f,true))
        assertFalse(DrawerGesturePolicy.settleOpen(.64f,false))
        assertTrue(DrawerGesturePolicy.settleOpen(.65f,false))
    }

    @Test fun allLibraryDestinationsAreEnabledButJournalFollowsGate(){
        val public=DrawerEntry.entries.toSet()-setOf(DrawerEntry.JOURNAL, DrawerEntry.DIAGNOSTICS)
        assertEquals(public,drawerAvailableEntries(false))
        assertEquals(DrawerEntry.entries.toSet(),drawerAvailableEntries(true))
        assertTrue(setOf(DrawerEntry.FAVORITES,DrawerEntry.PRIVATE,DrawerEntry.ARCHIVE,
            DrawerEntry.COLLECTIONS,DrawerEntry.TRASH).all{it in public})
        val defaults=DrawerConfiguration.DEFAULT
        assertEquals(DrawerEntry.entries.take(13), defaults.entries(true))
        assertEquals(DrawerEntry.entries.take(12), defaults.entries(false))
        val customized=defaults.setVisible(DrawerEntry.HOME,false)
            .setVisible(DrawerEntry.DIAGNOSTICS,true)
            .setVisible(DrawerEntry.MUSIC_DUPLICATES,true)
            .setVisible(DrawerEntry.SETTINGS,false)
            .move(DrawerEntry.MUSIC_DUPLICATES,DrawerEntry.HOME)
        assertEquals(DrawerEntry.MUSIC_DUPLICATES,customized.entries(false).first())
        assertFalse(DrawerEntry.HOME in customized.entries(true))
        assertTrue(DrawerEntry.SETTINGS in customized.entries(false))
        assertFalse(DrawerEntry.DIAGNOSTICS in customized.entries(false))
        assertTrue(DrawerEntry.DIAGNOSTICS in customized.entries(true))
        assertEquals(customized,DrawerConfiguration.decode(customized.encode()))
        val migrated=DrawerConfiguration.decode("MUSIC:1,SETTINGS:0,MUSIC:0,UNKNOWN:1")
        assertEquals(DrawerEntry.MUSIC,migrated.order.first())
        assertTrue(DrawerEntry.MUSIC in migrated.visible)
        assertTrue(DrawerEntry.SETTINGS in migrated.visible)
        assertEquals(DrawerEntry.entries.toSet(),migrated.order.toSet())
        assertEquals(DrawerEntry.entries.size,migrated.order.size)
        assertFalse(DrawerEntry.VK_IMAGES in migrated.visible)
        assertEquals(defaults,DrawerConfiguration.decode("garbage"))
    }
}
