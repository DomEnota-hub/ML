package com.domenota.medialoader.data.catalog.visibility

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.annotation.RequiresApi
import java.io.File

/** Explicitly requested only when the user invokes folder visibility. A shared,
 * non-indexed directory must not disappear when ML is uninstalled/cleared. */
internal object FolderVisibilityStorageAccess {
    const val RELATIVE_PATH = "Documents/MediaLoaderHidden/"
    fun supported(): Boolean = Build.VERSION.SDK_INT >= 30
    fun granted(): Boolean = Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    @RequiresApi(30)
    fun permissionIntent(context: Context) = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        Uri.parse("package:${context.packageName}"))
    @Suppress("DEPRECATION")
    fun store(): HiddenFolderFileStore {
        check(granted()) { "Для скрытых папок разрешите управление всеми файлами в настройках Android" }
        check(Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED) { "Общее хранилище недоступно" }
        val primary = Environment.getExternalStorageDirectory().canonicalFile
        val root = File(primary,RELATIVE_PATH.removeSuffix("/")).absoluteFile
        check(root.canonicalFile == root && root.toPath().startsWith(primary.toPath()))
        return HiddenFolderFileStore(root)
    }
}
