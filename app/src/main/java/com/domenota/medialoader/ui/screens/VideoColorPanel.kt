package com.domenota.medialoader.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import com.domenota.medialoader.ui.components.AppSlider as Slider
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.VideoColorAdjustments
import com.domenota.medialoader.data.editor.VideoColorGrade
import com.domenota.medialoader.data.editor.VideoFilterPreset
import com.domenota.medialoader.data.editor.PhotoEffect
import com.domenota.medialoader.data.editor.PhotoEffectCategory
import com.domenota.medialoader.data.editor.SharedVideoFilterCatalog
import com.domenota.medialoader.data.editor.VideoClipDraft
import java.util.Locale
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.platform.testTag
import kotlin.math.roundToInt

internal fun videoFilterHasIntensity(color: VideoColorGrade): Boolean =
    color.photoEffect?.let { it != PhotoEffect.NONE } ?: (color.preset != VideoFilterPreset.ORIGINAL)

internal fun videoFilterSelected(color: VideoColorGrade, effect: PhotoEffect): Boolean =
    color.photoEffect == effect || (color.photoEffect == null &&
        color.preset == VideoFilterPreset.ORIGINAL && effect == PhotoEffect.NONE)

/** Only the active clip is edited; filters and manual color changes compose non-destructively. */
@Composable
internal fun VideoColorPanel(
    color: VideoColorGrade,
    enabled: Boolean,
    onColorChange: (VideoColorGrade) -> Unit,
    manualByDefault: Boolean = false,
    clip: VideoClipDraft? = null,
) {
    if (manualByDefault) {
        VideoReferenceAdjustmentsPanel(color.reference, enabled) {
            onColorChange(color.copy(reference = it))
        }
        return
    }
    var group by remember { mutableStateOf(PhotoEffectCategory.ALL) }
    val context = LocalContext.current
    val favoriteEffects = remember(context) {
        context.getSharedPreferences("ml_filter_favorites", android.content.Context.MODE_PRIVATE)
            .getStringSet("selected", emptySet()).orEmpty()
            .mapNotNull { name -> PhotoEffect.entries.find { it.name == name } }.toSet()
    }
    val edge = (94 * androidx.compose.ui.platform.LocalDensity.current.density)
        .roundToInt().coerceIn(256, 384)
    // One source frame for the entire strip, scoped to this editor panel. Never retain
    // private pixels in the global thumbnail cache. Changing clips or trim resets it.
    val frameKey = clip?.let { VideoFilterFrame.key(it, edge) }
    var frame by remember(frameKey) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(frameKey) {
        val active = clip ?: return@LaunchedEffect
        var pending: Bitmap? = null
        try {
            withContext(Dispatchers.IO) { pending = VideoFilterFrame.read(context, active, edge) }
            frame = pending
            pending = null
        } finally { pending?.recycle() }
    }
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        PhotoEffectCategory.entries.filter { it != PhotoEffectCategory.POPULAR }.forEach { category ->
            FilterChip(selected = group == category, enabled = enabled,
                onClick = { group = category }, label = { Text(category.label) })
        }
    }
    // Lazily render only visible cards using the exact common photo preset renderer.
    LazyRow(Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SharedVideoFilterCatalog.forCategory(group, favoriteEffects), key = { it.name }) { effect ->
            EditorFilterCard(effect = effect, source = frame,
                selected = videoFilterSelected(color, effect), enabled = enabled,
                onClick = { onColorChange(color.copy(photoEffect = effect)) })
        }
    }
    // Original has no strength to adjust. Hide rather than show a misleading,
    // faded 100% control. A legacy non-original preset still supports intensity.
    if (videoFilterHasIntensity(color)) {
        val percent = (color.intensity * 100).roundToInt()
        Text("Сила фильтра: $percent %", style = MaterialTheme.typography.bodySmall)
        Slider(value = color.intensity, onValueChange = {
            onColorChange(color.copy(intensity = it))
        }, enabled = enabled, valueRange = 0f..1f,
            modifier = Modifier.testTag("video-filter-intensity"), accentColor = videoToolYellow)
    }

}

@Composable
internal fun VideoLegacyColorControls(color: VideoColorGrade, enabled: Boolean,
    onColorChange: (VideoColorGrade) -> Unit) {
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
