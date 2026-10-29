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

    /** Relative to the storage root, always ends with '/' and starts inside Download/ (MediaStore rule). */
    var relativeDirectory: String
        get() = prefs.getString(KEY, null)?.takeIf(::isValid) ?: StorageNaming.DEFAULT_RELATIVE_DIRECTORY
        set(value) {
            require(isValid(value)) { "Folder must be inside Download/" }
            prefs.edit().putString(KEY, value).remove(TREE_URI).remove(TREE_NAME).apply()
        }

    var treeUri: String?
        get() = prefs.getString(TREE_URI, null)
        set(value) { prefs.edit().putString(TREE_URI, value).apply() }

    var treeName: String?
        get() = prefs.getString(TREE_NAME, null)
        set(value) { prefs.edit().putString(TREE_NAME, value).apply() }

    private fun isValid(value: String): Boolean =
        value.startsWith("Download/") && value.endsWith("/") && ".." !in value && '\\' !in value

    private companion object {
        const val KEY = "relative_directory"
        const val TREE_URI = "tree_uri"
        const val TREE_NAME = "tree_name"
    }
}
