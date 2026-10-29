package com.domenota.medialoader.ui.theme

import androidx.compose.material3.MaterialTheme
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

private val MediaLoaderColors = darkColorScheme(
    primary = AccentPurple,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF332260),
    onPrimaryContainer = Color(0xFFE9DFFF),
    secondary = AccentPurpleSoft,
    background = AppBackground,
    onBackground = Color(0xFFF4F6FB),
    surface = AppSurface,
    onSurface = Color(0xFFF4F6FB),
    surfaceVariant = AppSurfaceElevated,
    onSurfaceVariant = MutedText,
    outline = AppOutline,
    error = ErrorRed,
)
private val LightColors = lightColorScheme(
    primary = Color(0xFF762DFF),
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
            content()
        },
    )
}
