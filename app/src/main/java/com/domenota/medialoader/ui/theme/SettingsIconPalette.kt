package com.domenota.medialoader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Settings accents belong to actions, not to the global purple theme.
 * All seven top-level groups have distinct foreground/container pairs in both
 * day and night mode. Brand icons keep their own identity.
 */
data class SettingsIconSwatch(val foreground: Color, val container: Color)

/** Semantics are stable while the material theme determines the actual palette. */
enum class SettingIconTone {
    ACCOUNTS, DOWNLOAD, BULK, VK_COMMUNITY, EQUALIZER, MUSIC_SETTINGS, APPEARANCE,
    PURPLE, BLUE, AMBER, GREEN, RED, NEUTRAL, FOLDER, NOTIFICATIONS, INSTAGRAM,
    CLOSE_PICKER, MUSIC, RECOGNITION, RENAME, THEME, PLAYER,
    TRASH, CLEAR_HISTORY, YTDLP, ABOUT;

    fun swatch(dark: Boolean): SettingsIconSwatch = when (this) {
        ACCOUNTS, PURPLE, THEME ->
            if (dark) SettingsIconSwatch(Color(0xFFB58AFF), Color(0xFF342545))
            else SettingsIconSwatch(Color(0xFF6F36BB), Color(0xFFEEE2FF))
        DOWNLOAD, BLUE, FOLDER, PLAYER, YTDLP ->
            if (dark) SettingsIconSwatch(Color(0xFF59B6FF), Color(0xFF173650))
            else SettingsIconSwatch(Color(0xFF145FB5), Color(0xFFDFEFFD))
        BULK ->
            if (dark) SettingsIconSwatch(Color(0xFFFFAC79), Color(0xFF422B24))
            else SettingsIconSwatch(Color(0xFFAD4E22), Color(0xFFFFE8DA))
        VK_COMMUNITY ->
            if (dark) SettingsIconSwatch(Color(0xFF44D6D0), Color(0xFF163F40))
            else SettingsIconSwatch(Color(0xFF086F75), Color(0xFFD8F5F1))
        EQUALIZER, GREEN, MUSIC, TRASH, RENAME ->
            if (dark) SettingsIconSwatch(Color(0xFF66D99B), Color(0xFF173E30))
            else SettingsIconSwatch(Color(0xFF177546), Color(0xFFE0F5E8))
        MUSIC_SETTINGS ->
            if (dark) SettingsIconSwatch(Color(0xFFF197D5), Color(0xFF42243B))
            else SettingsIconSwatch(Color(0xFF9D2B76), Color(0xFFFDE6F4))
        APPEARANCE, AMBER, NOTIFICATIONS ->
            if (dark) SettingsIconSwatch(Color(0xFFFFD269), Color(0xFF44361A))
            else SettingsIconSwatch(Color(0xFF916115), Color(0xFFFFF0C6))
        INSTAGRAM ->
            if (dark) SettingsIconSwatch(Color(0xFFFFA2BD), Color(0xFF452631))
            else SettingsIconSwatch(Color(0xFFAB305F), Color(0xFFFDE2EF))
        RECOGNITION ->
            if (dark) SettingsIconSwatch(Color(0xFF69D7DF), Color(0xFF174149))
            else SettingsIconSwatch(Color(0xFF006E78), Color(0xFFDDF6F7))
        CLOSE_PICKER ->
            if (dark) SettingsIconSwatch(Color(0xFFA9B9FF), Color(0xFF292D4C))
            else SettingsIconSwatch(Color(0xFF4C56A3), Color(0xFFE9ECFF))
        RED, CLEAR_HISTORY ->
            if (dark) SettingsIconSwatch(Color(0xFFFF8791), Color(0xFF48252D))
            else SettingsIconSwatch(Color(0xFFB43149), Color(0xFFFFE4E9))
        NEUTRAL, ABOUT ->
            if (dark) SettingsIconSwatch(Color(0xFFB2BBD1), Color(0xFF2A303E))
            else SettingsIconSwatch(Color(0xFF515C73), Color(0xFFE9ECF3))
    }
}
