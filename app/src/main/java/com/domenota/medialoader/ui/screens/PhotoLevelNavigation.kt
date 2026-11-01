package com.domenota.medialoader.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.domenota.medialoader.data.editor.HslRange

/** Presentation-only grouping. The PhotoColorAdjustments model is unchanged. */
internal enum class PhotoLevelGroup(val title: String) {
    TONE("Тон"), COLORS("Цвета"), DETAILS("Детализация")
}

internal fun photoLevelGroup(control: PhotoLevelControl): PhotoLevelGroup = when (control) {
    PhotoLevelControl.EXPOSURE, PhotoLevelControl.BRIGHTNESS,
    PhotoLevelControl.CONTRAST, PhotoLevelControl.SHADOWS,
    PhotoLevelControl.HIGHLIGHTS, PhotoLevelControl.BLACK_POINT,
    PhotoLevelControl.CURVE, PhotoLevelControl.RGB_CURVES -> PhotoLevelGroup.TONE

    PhotoLevelControl.SATURATION, PhotoLevelControl.VIBRANCE,
    PhotoLevelControl.WARMTH, PhotoLevelControl.TINT,
    PhotoLevelControl.HSL -> PhotoLevelGroup.COLORS

    PhotoLevelControl.SHARPNESS, PhotoLevelControl.CLARITY,
    PhotoLevelControl.GLOW, PhotoLevelControl.VIGNETTE,
    PhotoLevelControl.DISPERSION -> PhotoLevelGroup.DETAILS
}

/** Every level control gets a distinctive pictogram instead of one Tune icon. */
internal fun photoLevelIcon(control: PhotoLevelControl): ImageVector = when (control) {
    PhotoLevelControl.EXPOSURE -> Icons.Rounded.Exposure
    PhotoLevelControl.BRIGHTNESS -> Icons.Rounded.Brightness6
    PhotoLevelControl.CONTRAST -> Icons.Rounded.Contrast
    PhotoLevelControl.SATURATION -> Icons.Rounded.ColorLens
    PhotoLevelControl.VIBRANCE -> Icons.Rounded.AutoAwesome
    PhotoLevelControl.WARMTH -> Icons.Rounded.Thermostat
    PhotoLevelControl.TINT -> Icons.Rounded.Gradient
    PhotoLevelControl.SHADOWS -> Icons.Rounded.NightsStay
    PhotoLevelControl.HIGHLIGHTS -> Icons.Rounded.LightMode
    PhotoLevelControl.BLACK_POINT -> Icons.Rounded.Circle
    PhotoLevelControl.CURVE -> Icons.Rounded.ShowChart
    PhotoLevelControl.SHARPNESS -> Icons.Rounded.Details
    PhotoLevelControl.CLARITY -> Icons.Rounded.CenterFocusStrong
    PhotoLevelControl.RGB_CURVES -> Icons.Rounded.Timeline
    PhotoLevelControl.HSL -> Icons.Rounded.Palette
    PhotoLevelControl.GLOW -> Icons.Rounded.Flare
    PhotoLevelControl.VIGNETTE -> Icons.Rounded.FilterCenterFocus
    PhotoLevelControl.DISPERSION -> Icons.Rounded.BlurOn
}

/** HSL keeps the eight existing ranges and independently saved corrections. */
internal fun photoHslChannelColor(range: HslRange): Color = when (range) {
    HslRange.RED -> Color(0xFFE54B55)
    HslRange.ORANGE -> Color(0xFFF29B36)
    HslRange.YELLOW -> Color(0xFFE8C94F)
    HslRange.GREEN -> Color(0xFF53B46D)
    HslRange.CYAN -> Color(0xFF41B9C8)
    HslRange.BLUE -> Color(0xFF4888D8)
    HslRange.PURPLE -> Color(0xFF9870D6)
    HslRange.MAGENTA -> Color(0xFFD86EAE)
}
