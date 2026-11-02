package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.VideoColorAdjustments
import com.domenota.medialoader.data.editor.VideoColorGrade
import com.domenota.medialoader.data.editor.VideoFilterGroup
import com.domenota.medialoader.data.editor.VideoFilterPreset
import com.domenota.medialoader.data.editor.PhotoEffect
import com.domenota.medialoader.data.editor.PhotoEffectCategory
import com.domenota.medialoader.data.editor.SharedVideoFilterCatalog
import com.domenota.medialoader.data.editor.VideoClipDraft
import java.util.Locale

/** Only the active clip is edited; filters and manual color changes compose non-destructively. */
@Composable
internal fun VideoColorPanel(
    color: VideoColorGrade,
    enabled: Boolean,
    onColorChange: (VideoColorGrade) -> Unit,
    manualByDefault: Boolean = false,
    clip: VideoClipDraft? = null,
) {
    var showManual by remember { mutableStateOf(manualByDefault) }
    LaunchedEffect(manualByDefault) { showManual = manualByDefault }
    var group by remember { mutableStateOf(PhotoEffectCategory.POPULAR) }
    val context = LocalContext.current
    val favoriteEffects = remember(context) {
        context.getSharedPreferences("ml_filter_favorites", android.content.Context.MODE_PRIVATE)
            .getStringSet("selected", emptySet()).orEmpty()
            .mapNotNull { name -> PhotoEffect.entries.find { it.name == name } }.toSet()
    }
    var thumbs by remember(clip?.sourceUri, clip?.inMs) {
        mutableStateOf<Map<PhotoEffect, Bitmap>>(emptyMap())
    }
    // Render true per-preset photo thumbnails off the main thread. Both editors
    // enumerate the same PhotoEffectCatalog; no Xiaomi demo list or fake icons.
    LaunchedEffect(clip?.sourceUri, clip?.inMs, group) {
        val active = clip ?: return@LaunchedEffect
        val selection = SharedVideoFilterCatalog.forCategory(group, favoriteEffects)
        thumbs = withContext(Dispatchers.IO) {
            selection.mapNotNull { effect ->
                VideoThumbnailMemoryCache.photo(context, active, effect)
                    ?.let { effect to it }
            }.toMap()
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Цвет выбранного клипа", style = MaterialTheme.typography.titleSmall)
        TextButton(onClick = { onColorChange(VideoColorGrade()) },
            enabled = enabled && !color.isNeutral) { Text("Сбросить") }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = !showManual, onClick = { showManual = false },
            label = { Text("Фильтры") })
        FilterChip(selected = showManual, onClick = { showManual = true },
            label = { Text("Коррекция") })
    }
    if (!showManual) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            PhotoEffectCategory.entries.forEach { category ->
                FilterChip(selected = group == category, enabled = enabled,
                    onClick = { group = category }, label = { Text(category.label) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SharedVideoFilterCatalog.forCategory(group, favoriteEffects).forEach { effect ->
                val chosen = (color.photoEffect ?: PhotoEffect.NONE) == effect
                Surface(
                    onClick = { onColorChange(color.copy(photoEffect = effect)) },
                    enabled = enabled,
                    color = if (chosen) videoToolYellow else Color(0xFF2C2C2C),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.width(82.dp).height(87.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val thumb = thumbs[effect]
                        if (thumb != null) {
                            Image(bitmap = thumb.asImageBitmap(), contentDescription = effect.label,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().height(59.dp)
                                    .background(Color(0xFF171717)))
                        } else {
                            Box(Modifier.fillMaxWidth().height(59.dp)
                                .background(Color(0xFF252525))) {
                                Text(effect.label.take(1), Modifier.align(Alignment.Center),
                                    color = Color.LightGray)
                            }
                        }
                        Text(effect.label,
                            maxLines = 1, color = if (chosen) Color.Black else Color.White,
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val percent = (color.intensity * 100).toInt()
        Text("Сила фильтра: $percent %", style = MaterialTheme.typography.bodySmall)
        Slider(value = color.intensity, onValueChange = {
            onColorChange(color.copy(intensity = it))
        }, enabled = enabled && (color.photoEffect != null &&
            color.photoEffect != PhotoEffect.NONE ||
            color.photoEffect == null && color.preset != VideoFilterPreset.ORIGINAL),
            valueRange = 0f..1f)
        Text("Ручные настройки сохраняются при смене фильтра.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        val manual = color.manual
        ColorAdjustmentSlider("Яркость", manual.brightness, -.5f.. .5f, enabled) {
            onColorChange(color.copy(manual = manual.copy(brightness = it)))
        }
        ColorAdjustmentSlider("Контраст", manual.contrast, -.8f.. .8f, enabled) {
            onColorChange(color.copy(manual = manual.copy(contrast = it)))
        }
        ColorAdjustmentSlider("Насыщенность", manual.saturation, -100f..100f, enabled) {
            onColorChange(color.copy(manual = manual.copy(saturation = it)))
        }
        ColorAdjustmentSlider("Тон", manual.hue, -180f..180f, enabled) {
            onColorChange(color.copy(manual = manual.copy(hue = it)))
        }
        ColorAdjustmentSlider("Светлота", manual.lightness, -75f..75f, enabled) {
            onColorChange(color.copy(manual = manual.copy(lightness = it)))
        }
        Text("Цветовые каналы RGB", style = MaterialTheme.typography.titleSmall)
        ColorAdjustmentSlider("Красный", manual.red, 0f..2f, enabled) {
            onColorChange(color.copy(manual = manual.copy(red = it)))
        }
        ColorAdjustmentSlider("Зелёный", manual.green, 0f..2f, enabled) {
            onColorChange(color.copy(manual = manual.copy(green = it)))
        }
        ColorAdjustmentSlider("Синий", manual.blue, 0f..2f, enabled) {
            onColorChange(color.copy(manual = manual.copy(blue = it)))
        }
        TextButton(onClick = {
            onColorChange(color.copy(manual = VideoColorAdjustments()))
        }, enabled = enabled && !manual.isNeutral) { Text("Сбросить ручную коррекцию") }
    }
}

@Composable
private fun ColorAdjustmentSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onChange: (Float) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text("%.2f".format(Locale.US, value), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange, enabled = enabled, valueRange = range)
}
