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
import com.domenota.medialoader.data.playback.CrossfadePreferences
import kotlin.math.roundToInt

@Composable
internal fun CrossfadeControls() {
    val context = LocalContext.current
    val preferences = remember(context) { CrossfadePreferences.get(context) }
    val value by preferences.state.collectAsState()
    HorizontalDivider()
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Плавный переход (Crossfade)", fontSize = 14.sp)
            Text("Общие настройки воспроизведения", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(value.enabled, { preferences.set(value.copy(enabled = it)) },
            Modifier.semantics { contentDescription = "Включить плавный переход" })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Длительность", fontSize = 13.sp)
        Text("${value.seconds} с", fontSize = 13.sp)
    }
    Slider(value.seconds.toFloat(), { preferences.set(value.copy(seconds = it.roundToInt().coerceIn(1,12))) },
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Длительность плавного перехода" },
        enabled = value.enabled, valueRange = 1f..12f, steps = 10)
}
