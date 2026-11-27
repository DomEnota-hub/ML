package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.data.library.AppMode
import org.junit.Assert.*
import org.junit.Test

class DrawerPolicyTest{
    @Test fun taskCenterMigratesAheadOfServiceEntries(){
        val legacy=DrawerConfiguration.decode("HOME:1,MUSIC:1,SETTINGS:1,JOURNAL:1,TASK_CENTER:1")
        assertTrue(legacy.order.indexOf(DrawerEntry.TASK_CENTER)<legacy.order.indexOf(DrawerEntry.SETTINGS))
        assertTrue(legacy.order.indexOf(DrawerEntry.TASK_CENTER)<legacy.order.indexOf(DrawerEntry.JOURNAL))
        assertEquals(legacy,DrawerConfiguration.decode(legacy.encode()))
    }
    @Test fun onlyMainRoutesAndActualScreenAdmissionCanOpen(){
        for(mode in AppMode.entries){
            for(route in listOf("home","downloads","simple-archive",FAVORITES_ROUTE,"private","collections","music-duplicates","task-center") + (if(mode==AppMode.MUSIC)listOf("settings") else emptyList())){
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
        assertEquals(DrawerEntry.TASK_CENTER,drawerActiveEntry("task-center",AppMode.MUSIC))
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
        val public=DrawerEntry.entries.toSet()-setOf(DrawerEntry.JOURNAL,DrawerEntry.DIAGNOSTICS,DrawerEntry.BULK_DOWNLOAD,DrawerEntry.VK_IMAGES)
        assertEquals(public,drawerAvailableEntries(false))
        assertEquals(public+DrawerEntry.JOURNAL,drawerAvailableEntries(true))
        assertEquals(DrawerEntry.entries.toSet()-DrawerEntry.DIAGNOSTICS,drawerAvailableEntries(true,true))
        assertTrue(setOf(DrawerEntry.FAVORITES,DrawerEntry.PRIVATE,DrawerEntry.ARCHIVE,
            DrawerEntry.COLLECTIONS,DrawerEntry.TRASH).all{it in public})
        val defaults=DrawerConfiguration.DEFAULT
        assertEquals(DrawerEntry.entries.take(14), defaults.entries(true))
        assertEquals(DrawerEntry.entries.take(13), defaults.entries(false))
        val customized=defaults.setVisible(DrawerEntry.HOME,false)
            .setVisible(DrawerEntry.DIAGNOSTICS,true)
            .setVisible(DrawerEntry.MUSIC_DUPLICATES,true)
            .setVisible(DrawerEntry.SETTINGS,false)
            .move(DrawerEntry.MUSIC_DUPLICATES,DrawerEntry.HOME)
        assertEquals(DrawerEntry.MUSIC_DUPLICATES,customized.entries(false).first())
        assertTrue(DrawerEntry.HOME in customized.entries(true))
        assertTrue(mandatoryDrawerEntries.all { it in customized.entries(false) })
        assertTrue(DrawerEntry.SETTINGS in customized.entries(false))
        assertFalse(DrawerEntry.DIAGNOSTICS in customized.entries(false))
        assertFalse(DrawerEntry.DIAGNOSTICS in customized.entries(true))
        assertFalse(DrawerEntry.DIAGNOSTICS in customized.visible)
        val legacyDiagnostics=DrawerConfiguration.decode("DIAGNOSTICS:1,JOURNAL:1,HOME:1")
        assertFalse(DrawerEntry.DIAGNOSTICS in legacyDiagnostics.visible)
        assertFalse(DrawerEntry.DIAGNOSTICS in legacyDiagnostics.entries(true))
        assertFalse(DrawerEntry.DIAGNOSTICS in legacyDiagnostics.entries(false))
        assertTrue(DrawerEntry.JOURNAL in legacyDiagnostics.entries(true))
        assertFalse(DrawerEntry.JOURNAL in legacyDiagnostics.entries(false))
        assertEquals(legacyDiagnostics,DrawerConfiguration.decode(legacyDiagnostics.encode()))
        assertEquals(customized,DrawerConfiguration.decode(customized.encode()))
        val migrated=DrawerConfiguration.decode("MUSIC:0,SETTINGS:0,MUSIC:1,HOME:0,GALLERY:0,UNKNOWN:1")
        assertEquals(DrawerEntry.MUSIC,migrated.order.first())
        assertTrue(DrawerEntry.MUSIC in migrated.visible)
        assertTrue(mandatoryDrawerEntries.all { it in migrated.visible })
        assertTrue(mandatoryDrawerEntries.all { it in migrated.entries(false) })
        assertEquals(migrated,migrated.setVisible(DrawerEntry.GALLERY,false).setVisible(DrawerEntry.MUSIC,false))
        assertEquals(DrawerEntry.GALLERY,migrated.move(DrawerEntry.GALLERY,DrawerEntry.MUSIC).order.first())
        assertEquals(DrawerEntry.entries.toSet(),migrated.order.toSet())
        assertEquals(DrawerEntry.entries.size,migrated.order.size)
        assertFalse(DrawerEntry.VK_IMAGES in migrated.visible)
        assertEquals(defaults,DrawerConfiguration.decode("garbage"))
    }
    @Test fun hiddenAdvancedToolsRetainConfiguredOrderAndVisibility(){
        val initial=DrawerConfiguration.DEFAULT.setVisible(DrawerEntry.BULK_DOWNLOAD,true)
            .setVisible(DrawerEntry.VK_IMAGES,true).move(DrawerEntry.VK_IMAGES,DrawerEntry.DOWNLOADS)
        val hidden=initial.entries(true,false)
        assertFalse(DrawerEntry.BULK_DOWNLOAD in hidden)
        assertFalse(DrawerEntry.VK_IMAGES in hidden)
        val restored=DrawerConfiguration.decode(initial.encode())
        assertEquals(initial,restored)
        assertEquals(initial.order.filter{it in initial.visible && it in drawerAvailableEntries(true,true)},restored.entries(true,true))
        assertTrue(DrawerEntry.BULK_DOWNLOAD in restored.entries(true,true))
        assertTrue(DrawerEntry.VK_IMAGES in restored.entries(true,true))
        assertFalse(DrawerEntry.BULK_DOWNLOAD in DrawerConfiguration.DEFAULT.entries(true,true))
    }

    @Test fun dividersMigrateWithoutReorderingCustomLayoutAndPersistRemoval() {
        val old=DrawerEntry.entries.joinToString(","){it.name+":1"}
        val standard=DrawerConfiguration.decode(old)
        assertEquals(DrawerEntry.entries.toList(),standard.order)
        assertEquals(2,standard.layout.count{DrawerConfiguration.isDivider(it)})
        val custom=DrawerConfiguration.decode("MUSIC:1,HOME:1,GALLERY:1,SETTINGS:1")
        assertEquals(DrawerEntry.MUSIC,custom.order.first())
        assertFalse(custom.layout.any{DrawerConfiguration.isDivider(it)})
        val moved=standard.moveItem("DIVIDER_1",DrawerEntry.GALLERY.name)
        assertEquals(standard.order,moved.order)
        assertEquals(moved,DrawerConfiguration.decode(moved.encode()))
        val none=moved.removeDivider("DIVIDER_1").removeDivider("DIVIDER_2")
        assertFalse(none.layout.any{DrawerConfiguration.isDivider(it)})
        assertEquals(none,DrawerConfiguration.decode(none.encode()))
        assertEquals(none.order,none.addDivider().order)
    }
    @Test fun dividersRespectHiddenEntriesAndNeverRenderEmptyZones() {
        val defaults=DrawerConfiguration.DEFAULT
        val front=defaults.moveItem("DIVIDER_1",DrawerEntry.HOME.name)
        val back=front.moveItem("DIVIDER_2",DrawerEntry.VK_IMAGES.name)
        val display=back.displayItems(drawerAvailableEntries(false))
        assertFalse(display.any{DrawerConfiguration.isDivider(it)})
        val adjacent=defaults.moveItem("DIVIDER_2","DIVIDER_1")
        val shown=adjacent.displayItems(drawerAvailableEntries(false))
        assertEquals(1,shown.count{DrawerConfiguration.isDivider(it)})
        assertEquals(adjacent.entries(false).map{it.name},shown.filterNot{DrawerConfiguration.isDivider(it)})
        assertFalse("JOURNAL" in shown)
    }
}
