package com.domenota.medialoader.data.library

import android.content.Context
import java.io.File

/** Idempotent migration of disposable derived data from the retired gallery classifier. */
internal object RetiredAlbumCleanup {
    fun run(context: Context) {
        val root = context.noBackupFilesDir
        val index = File(root, "smart-album-facts-v1")
        // Never traverse symbolic links or an unexpected location.
        runCatching {
            if (index.exists() && index.canonicalFile == File(root.canonicalFile, index.name)) {
                index.listFiles().orEmpty().forEach { child ->
                    if (child.isFile && child.canonicalFile.parentFile == index.canonicalFile &&
                        child.name.matches(Regex("[0-9a-f]{64}\\.json(?:\\.bak|\\.new)?"))) child.delete()
                }
                index.delete() // succeeds only when empty
            }
            context.getSharedPreferences("smart_album_settings", Context.MODE_PRIVATE).edit().clear().apply()
        }
    }
}
