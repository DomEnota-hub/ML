package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class ModeShortcutsTest {
    @Test fun onlyPublicModesAccepted() {
        assertEquals(AppMode.GALLERY,ModeShortcuts.mode("com.domenota.medialoader.OPEN_GALLERY"))
        assertEquals(AppMode.MUSIC,ModeShortcuts.mode("com.domenota.medialoader.OPEN_MUSIC"))
        assertNull(ModeShortcuts.mode("private")); assertNull(ModeShortcuts.mode(null))
        assertNotEquals(ModeShortcuts.id(AppMode.GALLERY),ModeShortcuts.id(AppMode.MUSIC))
    }
}
