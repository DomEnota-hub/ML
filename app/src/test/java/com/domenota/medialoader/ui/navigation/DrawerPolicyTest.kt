package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.data.library.AppMode
import org.junit.Assert.*
import org.junit.Test

class DrawerPolicyTest{
    @Test fun onlyMainRoutesAndActualScreenAdmissionCanOpen(){
        for(mode in AppMode.entries){
            for(route in listOf("home","downloads")){
                assertTrue(drawerAllowed(route,mode,true,false))
                assertFalse(drawerAllowed(route,mode,false,false))
                assertFalse(drawerAllowed(route,mode,true,true))
            }
            for(route in listOf(null,"settings",MUSIC_SEARCH_ROUTE,"collections","trash","private","archive",
                "music-duplicates","visual-duplicates","viewer/{downloadId}","video-editor/{downloadId}",
                "photo-editor/{downloadId}","audio-editor/{downloadId}","batch-photo-editor","equalizer",
                "browser/{source}","preview","bulk","playlist-add/{playlistId}","unknown")){
                assertFalse(drawerAllowed(route,mode,true,false))
            }
        }
    }
    @Test fun activeEntryUsesRouteAndModeNotPlayback(){
        assertEquals(DrawerEntry.HOME,drawerActiveEntry("home",AppMode.DOWNLOADER))
        assertEquals(DrawerEntry.GALLERY,drawerActiveEntry("home",AppMode.GALLERY))
        assertEquals(DrawerEntry.MUSIC,drawerActiveEntry("home",AppMode.MUSIC))
        assertEquals(DrawerEntry.DOWNLOADS,drawerActiveEntry("downloads",AppMode.MUSIC))
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
}
