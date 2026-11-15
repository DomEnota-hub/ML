package com.domenota.medialoader.data.library
import android.Manifest
import org.junit.Assert.*
import org.junit.Test
class MediaWriteAccessTest {
    @Test fun readinessRequiresAllGrantsAndSupportedSdk(){
        for(sdk in listOf(31,32,33,36)){
            assertTrue(MediaWriteAccess.ready(sdk,true,true,true))
            assertFalse(MediaWriteAccess.ready(sdk,false,true,true))
            assertFalse(MediaWriteAccess.ready(sdk,true,false,true))
            assertFalse(MediaWriteAccess.ready(sdk,true,true,false))
        }
        assertFalse(MediaWriteAccess.ready(30,true,true,true))
        assertEquals(Manifest.permission.READ_EXTERNAL_STORAGE,MediaWriteAccess.audioPermission(31))
        assertEquals(Manifest.permission.READ_MEDIA_AUDIO,MediaWriteAccess.audioPermission(36))
    }
}
