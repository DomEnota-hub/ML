package com.domenota.medialoader.data.storage

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StorageSettingsPicturesTest {
    private val context = RuntimeEnvironment.getApplication() as Context
    private val preferences = context.getSharedPreferences("storage", Context.MODE_PRIVATE)

    @After fun reset() {
        preferences.edit().clear().commit()
    }

    @Test fun oldFolderPreferenceDoesNotMoveFilesOrWipeCustomHistory() {
        preferences.edit()
            .putString("relative_directory", "Download/MediaLoader/")
            .putString("tree_uri", "content://com.android.externalstorage.documents/tree/test")
            .putString("tree_name", "Old media")
            .commit()
        val settings = StorageSettings(context)
        assertEquals("Pictures/MediaLoader/", settings.relativeDirectory)
        // Migration of existing files is a separate explicit action. Reading
        // the new destination must not silently erase the prior saved value
        // or any SAF access chosen by the user.
        assertEquals("Download/MediaLoader/",
            preferences.getString("relative_directory", null))
        assertEquals("Old media", settings.treeName)
    }

    @Test fun updatingNewFolderDoesNotRewriteHistory() {
        preferences.edit().putString("relative_directory", "Download/Legacy/").commit()
        val settings = StorageSettings(context)
        settings.relativeDirectory = "Pictures/NewMedia/"
        assertEquals("Pictures/NewMedia/", settings.relativeDirectory)
        assertNull(settings.treeUri)
        assertEquals("Pictures/NewMedia/",
            preferences.getString("relative_directory", null))
    }
}
