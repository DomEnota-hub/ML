package com.domenota.medialoader.ui.components

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.domenota.medialoader.data.catalog.visibility.FolderVisibilityStorageAccess
import kotlinx.coroutines.CompletableDeferred

/** The active Activity owns every system consent. Cancellation never approves it. */
internal class FolderVisibilityAccessHost(
    val ensure: suspend () -> Unit,
    val approve: suspend (PendingIntent) -> Boolean,
)

@Composable
internal fun rememberFolderVisibilityAccessHost(): FolderVisibilityAccessHost {
    val context=LocalContext.current
    var permissions by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var storage by remember { mutableStateOf<CompletableDeferred<Unit>?>(null) }
    var consent by remember { mutableStateOf<CompletableDeferred<Boolean>?>(null) }
    val permissionLauncher=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions?.complete(Unit) }
    val storageLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { storage?.complete(Unit) }
    val consentLauncher=rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { consent?.complete(it.resultCode==Activity.RESULT_OK) }
    return FolderVisibilityAccessHost(ensure={
        check(Build.VERSION.SDK_INT>=30) { "Скрытие папок доступно с Android 11" }
        // The folder can contain any supported kind, including a mixture of all three.
        val required=if(Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.READ_MEDIA_IMAGES,Manifest.permission.READ_MEDIA_VIDEO,Manifest.permission.READ_MEDIA_AUDIO,Manifest.permission.ACCESS_MEDIA_LOCATION)
            else listOf(Manifest.permission.READ_EXTERNAL_STORAGE,Manifest.permission.ACCESS_MEDIA_LOCATION)
        fun missing()=required.filter { ContextCompat.checkSelfPermission(context,it)!=PackageManager.PERMISSION_GRANTED }
        if(missing().isNotEmpty()) {
            check(permissions==null)
            val result=CompletableDeferred<Unit>(); permissions=result
            try { permissionLauncher.launch(missing().toTypedArray()); result.await() } finally { permissions=null }
        }
        check(missing().isEmpty()) { "Нужен полный доступ к фото, видео, аудио и исходным метаданным" }
        if(!FolderVisibilityStorageAccess.granted()) {
            check(storage==null)
            val result=CompletableDeferred<Unit>(); storage=result
            try { storageLauncher.launch(FolderVisibilityStorageAccess.permissionIntent(context)); result.await() } finally { storage=null }
        }
        check(FolderVisibilityStorageAccess.granted()) { "Управление всеми файлами не разрешено" }
    },approve={ intent ->
        check(consent==null)
        val result=CompletableDeferred<Boolean>(); consent=result
        try { consentLauncher.launch(IntentSenderRequest.Builder(intent.intentSender).build()); result.await() }
        finally { consent=null }
    })
}
