package com.domenota.medialoader.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@org.robolectric.annotation.Config(sdk = [34])
@RunWith(RobolectricTestRunner::class)
class LibraryPreferencesTest {
    @Test fun modeAndEachSourcePersistIndependently() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library_modes", 0).edit().clear().commit()
        val prefs = LibraryPreferences(context)
        assertEquals(AppMode.DOWNLOADER, prefs.mode)
        assertEquals(LibrarySource.APP, prefs.source(AppMode.GALLERY))
        prefs.mode = AppMode.MUSIC
        prefs.setSource(AppMode.GALLERY, LibrarySource.PHONE)
        val restored = LibraryPreferences(context)
        assertEquals(AppMode.MUSIC, restored.mode)
        assertEquals(LibrarySource.PHONE, restored.source(AppMode.GALLERY))
        assertEquals(LibrarySource.APP, restored.source(AppMode.MUSIC))
    }
    @Test fun unknownPreferenceValuesFallBackSafely() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("library_modes", 0).edit().clear().putString("mode", "future")
            .putString("source_GALLERY", "future").commit()
        val prefs = LibraryPreferences(context)
        assertEquals(AppMode.DOWNLOADER, prefs.mode)
        assertEquals(LibrarySource.APP, prefs.source(AppMode.GALLERY))
    }
}
