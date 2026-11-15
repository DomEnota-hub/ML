package com.domenota.medialoader.data.library

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat

/** Android retains the authority to approve each MediaStore write request. */
internal object MediaWriteAccess {
    fun audioPermission(sdk: Int): String = if (sdk >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
    fun ready(sdk: Int, canManage: Boolean, audioRead: Boolean, mediaLocation: Boolean): Boolean =
        sdk >= 31 && canManage && audioRead && mediaLocation
    fun ready(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 && ready(Build.VERSION.SDK_INT,
        MediaStore.canManageMedia(context), granted(context,audioPermission(Build.VERSION.SDK_INT)),
        granted(context,Manifest.permission.ACCESS_MEDIA_LOCATION))
    fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
}
