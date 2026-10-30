package com.domenota.medialoader.ui.components

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/** A one-time offer, never an automatic grant or a repeated request on each deletion. */
@Composable
internal fun MediaManagementAccessPrompt(enabled: Boolean) {
    if (Build.VERSION.SDK_INT < 31 || !enabled) return
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("ui", 0) }
    var show by remember { mutableStateOf(!preferences.getBoolean("media_management_offer_seen_v1", false) &&
        !MediaStore.canManageMedia(context)) }
    fun dismiss() {
        preferences.edit().putBoolean("media_management_offer_seen_v1", true).apply()
        show = false
    }
    if (show) AlertDialog(onDismissRequest = ::dismiss,
        title = { Text("Управление медиафайлами") },
        text = { Text("Разрешите изменение и удаление фото, видео и музыки без повторных запросов Android. На следующем экране включите доступ для MediaLoader. Перед удалением приложение всё равно спросит подтверждение.") },
        confirmButton = { TextButton({
            dismiss()
            try { context.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA,
                Uri.parse("package:${context.packageName}"))) }
            catch (_: Exception) { Toast.makeText(context, "Откройте доступ через настройки приложения", Toast.LENGTH_LONG).show() }
        }) { Text("Разрешить") } },
        dismissButton = { TextButton(::dismiss) { Text("Не сейчас") } })
}
