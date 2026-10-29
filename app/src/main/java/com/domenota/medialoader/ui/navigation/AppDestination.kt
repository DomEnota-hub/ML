package com.domenota.medialoader.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

enum class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Главная", Icons.Rounded.Home),
    Downloads("downloads", "Загрузки", Icons.Rounded.Download),
    Settings("settings", "Настройки", Icons.Rounded.Settings),
}

const val PREVIEW_ROUTE = "preview"
const val BULK_ROUTE = "bulk"
const val BROWSER_ROUTE = "browser/{source}"
const val MEDIA_VIEWER_ROUTE = "viewer/{downloadId}"
const val VIDEO_EDITOR_ROUTE = "video-editor/{downloadId}"
const val AUDIO_EDITOR_ROUTE = "audio-editor/{downloadId}"
const val PHOTO_EDITOR_ROUTE = "photo-editor/{downloadId}"
