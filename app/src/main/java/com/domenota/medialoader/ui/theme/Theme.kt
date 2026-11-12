package com.domenota.medialoader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppBackground = Color(0xFF0D1016)
val AppSurface = Color(0xFF171B24)
val AppSurfaceElevated = Color(0xFF202531)
val AppOutline = Color(0xFF2C3340)
val AccentPurple = Color(0xFF7A4DFF)
val AccentPurpleSoft = Color(0xFF9B7BFF)
val MutedText = Color(0xFF9EA7B8)
val Success = Color(0xFF65D49A)
val ErrorRed = Color(0xFFFF6B78)

internal val AppGold = Color(0xFFFFBD19)
internal val LightAppAccent = Color(0xFF762DFF)
// Music and gallery already use this light library accent.
internal val LightMusicAccent = Color(0xFF7036CB)
private val GoldContainer = Color(0xFF4D3800)
private val GoldOnContainer = AppGold
private val GoldInk = Color(0xFF211800)

/** Legacy recognition tint is independent of the new application accent.
 * LibraryTheme overrides this with its own original colour. */
internal val LocalShazamResultColor = staticCompositionLocalOf { LightAppAccent }
internal fun originalShazamColor(dark: Boolean, library: Boolean = false): Color =
    if (library) Color(if (dark) 0xFFBB9CFF else 0xFF7036CB)
    else if (dark) AccentPurple else LightAppAccent

internal fun musicAccent(dark: Boolean): Color = if (dark) AppGold else LightMusicAccent
internal val musicPlaybackAccent: Color
    @Composable get() = musicAccent(MaterialTheme.colorScheme.background.luminance() < .5f)
internal val isDarkPalette: Boolean
    @Composable get() = MaterialTheme.colorScheme.background.luminance() < .5f

/** Apply to the full scheme, not only primary. Material3 defaults are violet. */
internal fun goldAccentScheme(base: ColorScheme): ColorScheme = base.copy(
    primary = AppGold, onPrimary = GoldInk,
    primaryContainer = GoldContainer, onPrimaryContainer = GoldOnContainer,
    inversePrimary = Color(0xFF815A00),
    secondary = AppGold, onSecondary = GoldInk,
    secondaryContainer = GoldContainer, onSecondaryContainer = GoldOnContainer,
    tertiary = AppGold, onTertiary = GoldInk,
    tertiaryContainer = GoldContainer, onTertiaryContainer = GoldOnContainer,
    surfaceTint = AppGold,
)

/** Editors retain their own black/white/gold palette even in light mode. */
internal fun editorColorScheme(): ColorScheme = goldAccentScheme(darkColorScheme(
    background = Color(0xFF101010), onBackground = Color(0xFFECECEC),
    surface = Color(0xFF242424), onSurface = Color(0xFFECECEC),
    surfaceVariant = Color(0xFF303030), onSurfaceVariant = Color(0xFFBDBDBD),
    outline = Color(0xFF606060), outlineVariant = Color(0xFF454545),
    surfaceContainerLowest = Color(0xFF080808), surfaceContainerLow = Color(0xFF191919),
    surfaceContainer = Color(0xFF242424), surfaceContainerHigh = Color(0xFF303030),
    surfaceContainerHighest = Color(0xFF383838), surfaceDim = Color(0xFF101010),
    surfaceBright = Color(0xFF383838), inverseSurface = Color(0xFFECECEC),
    inverseOnSurface = Color(0xFF242424),
))

internal val MediaLoaderColors = goldAccentScheme(darkColorScheme(
    background = AppBackground,
    onBackground = Color(0xFFF4F6FB),
    surface = AppSurface,
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = AppSurfaceElevated,
    onSurfaceVariant = MutedText,
    outline = AppOutline,
    error = ErrorRed,
    outlineVariant = Color(0xFF414855),
    surfaceContainerLowest = Color(0xFF090C11), surfaceContainerLow = AppBackground,
    surfaceContainer = AppSurface, surfaceContainerHigh = AppSurfaceElevated,
    surfaceContainerHighest = Color(0xFF292F3B), surfaceDim = AppBackground,
    surfaceBright = Color(0xFF303744), inverseSurface = Color(0xFFF4F6FB),
    inverseOnSurface = AppBackground,
))
internal val LightColors = lightColorScheme(
    primary = LightAppAccent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE9E0FF),
    onPrimaryContainer = Color(0xFF321D77),
    secondary = Color(0xFF6544B2),
    background = Color(0xFFF8F7FD),
    onBackground = Color(0xFF1D202A),
    surface = Color.White,
    onSurface = Color(0xFF1D202A),
    surfaceVariant = Color(0xFFF2EFFB),
    onSurfaceVariant = Color(0xFF565C69),
    outline = Color(0xFF9992AA),
    outlineVariant = Color(0xFFBBB5CC),
    error = Color(0xFFBB3348),
)

private val MediaLoaderTypography = Typography(
    headlineLarge = TextStyle(
        fontSize = 30.sp,
        lineHeight = 36.sp,
        fontWeight = FontWeight.Bold,
    ),
    headlineMedium = TextStyle(
        fontSize = 24.sp,
        lineHeight = 30.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontSize = 16.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp,
        lineHeight = 23.sp,
        fontWeight = FontWeight.Normal,
    ),
    bodyMedium = TextStyle(
        fontSize = 14.sp,
        lineHeight = 20.sp,
        fontWeight = FontWeight.Normal,
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
)

@Composable
fun MediaLoaderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("ui", 0)
    val mode = prefs.getString("theme", null)
        ?: if (prefs.contains("dark")) { if (prefs.getBoolean("dark", false)) "dark" else "light" } else "light"
    val dark = when (mode) { "dark" -> true; "system" -> isSystemInDarkTheme(); else -> false }
    MaterialTheme(
        colorScheme = if (dark) MediaLoaderColors else LightColors,
        typography = MediaLoaderTypography,
        content = {
            TransparentWindowNavigation()
            CompositionLocalProvider(LocalShazamResultColor provides originalShazamColor(dark)) { content() }
        },
    )
}
