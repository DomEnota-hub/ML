package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.playback.MusicLoudnessPreferences

@Composable
internal fun MusicLoudnessControls() {
    val context = LocalContext.current
    val preferences = remember(context) { MusicLoudnessPreferences.get(context) }
    val enabled by preferences.enabled.collectAsState()
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Нормализация громкости", fontSize = 14.sp)
            Text("Выравнивание громкости при воспроизведении. Исходные файлы не меняются.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(enabled, preferences::setEnabled, Modifier.semantics { contentDescription = "Нормализация громкости" })
    }
}
