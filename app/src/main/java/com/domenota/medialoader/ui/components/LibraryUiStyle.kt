package com.domenota.medialoader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.domenota.medialoader.ui.theme.AppGold
import com.domenota.medialoader.ui.theme.goldAccentScheme
import com.domenota.medialoader.ui.theme.LightMusicAccent
import com.domenota.medialoader.ui.theme.LocalShazamResultColor
import com.domenota.medialoader.ui.theme.originalShazamColor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Scoped native counterpart of the approved HTML A palette. */
@Composable
internal fun LibraryTheme(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val base = MaterialTheme.colorScheme
    val dark = base.background.luminance() < .5f
    val background = Color(if (dark) 0xFF0D0E12 else 0xFFF8F6FC)
    val foreground = Color(if (dark) 0xFFF1EFF6 else 0xFF201B29)
    val surface = Color(if (dark) 0xFF1B1B23 else 0xFFEEEBF4)
    val accent = if (dark) AppGold else LightMusicAccent
    MaterialTheme(colorScheme = (if (dark) goldAccentScheme(base) else base).copy(
        background = background, onBackground = foreground, surface = background, onSurface = foreground,
        surfaceVariant = surface, surfaceContainer = surface, surfaceContainerLow = surface,
        surfaceContainerHigh = surface, surfaceContainerHighest = surface,
        primary = accent, onPrimary = background, primaryContainer = accent.copy(alpha = .14f),
        onPrimaryContainer = accent, secondaryContainer = surface, onSecondaryContainer = accent,
        onSurfaceVariant = Color(if (dark) 0xFF92919F else 0xFF716B7C),
        outline = Color(if (dark) 0xFF30303C else 0xFFCCC5D6),
    )) { CompositionLocalProvider(LocalShazamResultColor provides originalShazamColor(dark, library = true)) { content() } }
}
