package com.domenota.medialoader.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import android.provider.Settings

/** Indeterminate visual only: no audio decoding, microphone or playback. */
@Composable
internal fun RecognitionEqualizer() {
    val context = LocalContext.current
    val reduced = remember(context) { runCatching {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }.getOrDefault(false) }
    val phase = if (reduced) 0.35f else {
        val transition = rememberInfiniteTransition(label = "recognition-wave")
        val value by transition.animateFloat(initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1250, easing = LinearEasing)), label = "phase")
        value
    }
    val accent = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(width = 94.dp, height = 76.dp).testTag("recognition-equalizer")) {
        val bar = 7.dp.toPx()
        val spacing = 7.dp.toPx()
        val center = size.height / 2f
        val heights = floatArrayOf(.48f, .75f, 1f, .75f, .48f)
        val offsets = floatArrayOf(.12f, .44f, .68f, .28f, .56f)
        val start = (size.width - (bar * 5 + spacing * 4)) / 2f
        heights.forEachIndexed { i, maxHeight ->
            val envelope = .28f + .72f * ((kotlin.math.sin((phase + offsets[i]) * Math.PI * 2.0).toFloat() + 1f) / 2f)
            val h = size.height * .87f * maxHeight * envelope
            drawRoundRect(accent, Offset(start + i * (bar + spacing), center - h / 2f),
                Size(bar, h), CornerRadius(bar / 2f, bar / 2f))
        }
    }
}
