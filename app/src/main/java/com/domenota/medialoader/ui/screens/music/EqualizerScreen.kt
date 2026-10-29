package com.domenota.medialoader.ui.screens.music

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.domenota.medialoader.data.playback.EqualizerSettings
import com.domenota.medialoader.ui.components.AppSlider
import kotlin.math.roundToInt

@Composable
internal fun EqualizerScreen(settings: EqualizerSettings, sampleRate: Int?, onSettings: (EqualizerSettings) -> Unit, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 17.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Эквалайзер", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 19.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Обработка звука", Modifier.weight(1f), fontSize = 14.sp)
                Switch(settings.enabled, { onSettings(settings.copy(enabled = it)) }, Modifier.semantics { contentDescription = "Включить эквалайзер" })
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (EqualizerSettings.PRESETS.keys + "Свой").forEach { preset ->
                    FilterChip(selected = settings.preset == preset, onClick = { onSettings(settings.withPreset(preset)) }, label = { Text(preset, fontSize = 12.sp) })
                }
            }
            Spacer(Modifier.height(16.dp))
            val labels = listOf("60 Гц", "230 Гц", "910 Гц", "3,6 кГц", "14 кГц")
            labels.forEachIndexed { index, label ->
                val supported = sampleRate == null || EqualizerSettings.FREQUENCIES[index] < sampleRate / 2.0
                val gain = settings.gainsDb[index].roundToInt()
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${if (gain > 0) "+" else ""}$gain дБ", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    AppSlider(settings.gainsDb[index], { onSettings(settings.withBand(index, it.roundToInt().toFloat())) },
                        Modifier.fillMaxWidth().semantics { contentDescription = label }, enabled = settings.enabled && supported, valueRange = -12f..12f)
                    if (!supported) Text("Недоступна при частоте текущего трека $sampleRate Гц", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Одна настройка для всех треков и плейлистов. Сохраняется и работает при фоновом воспроизведении.",
                Modifier.padding(vertical = 15.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton({ onSettings(settings.withPreset("Ровный")) }, Modifier.align(Alignment.CenterHorizontally)) { Text("Сбросить настройки") }
        }
    }
}
