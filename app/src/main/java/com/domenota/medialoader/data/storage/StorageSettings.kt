package com.domenota.medialoader.data.storage

import android.content.Context
import android.content.SharedPreferences
import com.domenota.medialoader.core.storage.StorageNaming

/**
 * The only source of the download folder. Everything else asks StorageManager, which asks this class,
 * so changing the folder in Settings later means adding a setter call here and nothing else.
 */
class StorageSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("storage", Context.MODE_PRIVATE)

    /** New photo/video destination is Pictures/. Legacy Download preferences map here without moving files. */
    var relativeDirectory: String
        get() = MediaStoragePolicy.upgradePreference(prefs.getString(KEY, null))
        set(value) {
            require(MediaStoragePolicy.validPictures(value)) { "Папка должна быть внутри Pictures/" }
            prefs.edit().putString(KEY, value).remove(TREE_URI).remove(TREE_NAME).apply()
        }

    var treeUri: String?
        get() = prefs.getString(TREE_URI, null)
        set(value) { prefs.edit().putString(TREE_URI, value).apply() }

    var treeName: String?
        get() = prefs.getString(TREE_NAME, null)
        set(value) { prefs.edit().putString(TREE_NAME, value).apply() }


    private companion object {
        const val KEY = "relative_directory"
        const val TREE_URI = "tree_uri"
        const val TREE_NAME = "tree_name"
    }
}
