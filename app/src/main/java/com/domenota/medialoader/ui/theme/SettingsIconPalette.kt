package com.domenota.medialoader.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Settings accents belong to actions, not to the global purple theme.
 * All seven top-level groups have distinct container pairs (former violet accents share the approved gold) in both
 * day and night mode. Brand icons keep their own identity.
 */
data class SettingsIconSwatch(val foreground: Color, val container: Color)

/** Semantics are stable while the material theme determines the actual palette. */
enum class SettingIconTone {
    ACCOUNTS, DOWNLOAD, BULK, VK_COMMUNITY, EQUALIZER, MUSIC_SETTINGS, APPEARANCE,
    PURPLE, BLUE, AMBER, GREEN, RED, NEUTRAL, FOLDER, NOTIFICATIONS, INSTAGRAM,
    CLOSE_PICKER, MUSIC, RECOGNITION, RENAME, THEME, PLAYER,
    TRASH, CLEAR_HISTORY, YTDLP, ABOUT, DIAGNOSTICS, LOG, INSTAGRAM_MODE,
    BACKUP_AUTH, DOWNLOAD_FOLDER, QUALITY, VIDEO_THUMBNAIL, BUILTIN_PLAYER,
    FILE_ACCESS, MEDIA_MANAGEMENT, ADVANCED_LOG, AI_MODELS, SHORTCUTS, SHORTCUT_STYLE, SHORTCUT_GALLERY, SHORTCUT_MUSIC;

    fun swatch(dark: Boolean): SettingsIconSwatch = when (this) {
        ACCOUNTS, PURPLE ->
            if (dark) SettingsIconSwatch(AppGold, Color(0xFF403111))
            else SettingsIconSwatch(Color(0xFF6F36BB), Color(0xFFEEE2FF))
        DOWNLOAD, BLUE ->
            if (dark) SettingsIconSwatch(Color(0xFF59B6FF), Color(0xFF173650))
            else SettingsIconSwatch(Color(0xFF145FB5), Color(0xFFDFEFFD))
        BULK ->
            if (dark) SettingsIconSwatch(Color(0xFFFFAC79), Color(0xFF422B24))
            else SettingsIconSwatch(Color(0xFFAD4E22), Color(0xFFFFE8DA))
        VK_COMMUNITY ->
            if (dark) SettingsIconSwatch(Color(0xFF44D6D0), Color(0xFF163F40))
            else SettingsIconSwatch(Color(0xFF086F75), Color(0xFFD8F5F1))
        GREEN ->
            if (dark) SettingsIconSwatch(Color(0xFF66D99B), Color(0xFF173E30))
            else SettingsIconSwatch(Color(0xFF177546), Color(0xFFE0F5E8))
        MUSIC_SETTINGS ->
            if (dark) SettingsIconSwatch(Color(0xFFF197D5), Color(0xFF42243B))
            else SettingsIconSwatch(Color(0xFF9D2B76), Color(0xFFFDE6F4))
        APPEARANCE ->
            if (dark) SettingsIconSwatch(Color(0xFF91ACFF), Color(0xFF263557))
            else SettingsIconSwatch(Color(0xFF345BB3), Color(0xFFE4EBFF))
        INSTAGRAM ->
            if (dark) SettingsIconSwatch(Color(0xFFFFA2BD), Color(0xFF452631))
            else SettingsIconSwatch(Color(0xFFAB305F), Color(0xFFFDE2EF))
        RECOGNITION ->
            if (dark) SettingsIconSwatch(Color(0xFF69D7DF), Color(0xFF174149))
            else SettingsIconSwatch(Color(0xFF006E78), Color(0xFFDDF6F7))
        CLOSE_PICKER ->
            if (dark) SettingsIconSwatch(Color(0xFFA9B9FF), Color(0xFF292D4C))
            else SettingsIconSwatch(Color(0xFF4C56A3), Color(0xFFE9ECFF))
        RED ->
            if (dark) SettingsIconSwatch(Color(0xFFFF8791), Color(0xFF48252D))
            else SettingsIconSwatch(Color(0xFFB43149), Color(0xFFFFE4E9))
        FOLDER ->
            if (dark) SettingsIconSwatch(Color(0xFF43DDD3), Color(0xFF174440))
            else SettingsIconSwatch(Color(0xFF087B77), Color(0xFFDCF7F2))
        PLAYER ->
            if (dark) SettingsIconSwatch(AppGold, Color(0xFF3C321B))
            else SettingsIconSwatch(Color(0xFF6C48B6), Color(0xFFEEE8FF))
        AI_MODELS ->
            if (dark) SettingsIconSwatch(Color(0xFFFFD16B), Color(0xFF46371E))
            else SettingsIconSwatch(Color(0xFF92651E), Color(0xFFFFF1D6))
        YTDLP ->
            if (dark) SettingsIconSwatch(Color(0xFF7ADEEB), Color(0xFF183D48))
            else SettingsIconSwatch(Color(0xFF096A85), Color(0xFFDDF3F8))
        EQUALIZER ->
            if (dark) SettingsIconSwatch(Color(0xFF74E1BB), Color(0xFF194438))
            else SettingsIconSwatch(Color(0xFF087257), Color(0xFFDCF7EB))
        RENAME ->
            if (dark) SettingsIconSwatch(Color(0xFFB7DB6A), Color(0xFF354324))
            else SettingsIconSwatch(Color(0xFF43660D), Color(0xFFEDF7D9))
        NOTIFICATIONS ->
            if (dark) SettingsIconSwatch(Color(0xFFF5E285), Color(0xFF454023))
            else SettingsIconSwatch(Color(0xFF82610B), Color(0xFFFFF7D5))
        DIAGNOSTICS ->
            if (dark) SettingsIconSwatch(Color(0xFFFF8791), Color(0xFF48252D))
            else SettingsIconSwatch(Color(0xFFB43149), Color(0xFFFFE4E9))
        LOG ->
            if (dark) SettingsIconSwatch(Color(0xFF8BAEFF), Color(0xFF253357))
            else SettingsIconSwatch(Color(0xFF3E5FAF), Color(0xFFE5EBFF))
        INSTAGRAM_MODE ->
            if (dark) SettingsIconSwatch(Color(0xFFC6D38C), Color(0xFF394127))
            else SettingsIconSwatch(Color(0xFF5C6B2E), Color(0xFFF0F4DD))
        MUSIC ->
            if (dark) SettingsIconSwatch(Color(0xFFFFC08A), Color(0xFF432C1C))
            else SettingsIconSwatch(Color(0xFF925118), Color(0xFFFFF0D9))
        TRASH ->
            if (dark) SettingsIconSwatch(Color(0xFFFF8D9A), Color(0xFF4A2833))
            else SettingsIconSwatch(Color(0xFFB13D54), Color(0xFFFFE3E8))
        CLEAR_HISTORY ->
            if (dark) SettingsIconSwatch(Color(0xFFE5A28E), Color(0xFF49312C))
            else SettingsIconSwatch(Color(0xFFA05743), Color(0xFFFAE9E2))
        AMBER ->
            if (dark) SettingsIconSwatch(Color(0xFFFFC46C), Color(0xFF48331A))
            else SettingsIconSwatch(Color(0xFF946018), Color(0xFFFFF1D4))
        THEME ->
            if (dark) SettingsIconSwatch(AppGold, Color(0xFF493A16))
            else SettingsIconSwatch(Color(0xFF8746C4), Color(0xFFF1E5FF))
        BACKUP_AUTH ->
            if (dark) SettingsIconSwatch(Color(0xFFFFD58C), Color(0xFF493321))
            else SettingsIconSwatch(Color(0xFF86550D), Color(0xFFFFF0CE))
        DOWNLOAD_FOLDER ->
            if (dark) SettingsIconSwatch(Color(0xFF54D6C2), Color(0xFF173F3A))
            else SettingsIconSwatch(Color(0xFF006F61), Color(0xFFDBF7EE))
        QUALITY ->
            if (dark) SettingsIconSwatch(Color(0xFF9ACBE8), Color(0xFF283E4B))
            else SettingsIconSwatch(Color(0xFF37677F), Color(0xFFE3F1F8))
        VIDEO_THUMBNAIL ->
            if (dark) SettingsIconSwatch(Color(0xFFFF9CBB), Color(0xFF462633))
            else SettingsIconSwatch(Color(0xFFA42B5B), Color(0xFFFFE1ED))
        BUILTIN_PLAYER ->
            if (dark) SettingsIconSwatch(Color(0xFF94C2FF), Color(0xFF263B4F))
            else SettingsIconSwatch(Color(0xFF275F9B), Color(0xFFE1EEFF))
        FILE_ACCESS ->
            if (dark) SettingsIconSwatch(Color(0xFF50E0AD), Color(0xFF19473B))
            else SettingsIconSwatch(Color(0xFF117451), Color(0xFFE2F7EC))
        MEDIA_MANAGEMENT ->
            if (dark) SettingsIconSwatch(Color(0xFFC4D6FF), Color(0xFF263955))
            else SettingsIconSwatch(Color(0xFF3F5F95), Color(0xFFE8EEFE))
        ADVANCED_LOG ->
            if (dark) SettingsIconSwatch(Color(0xFFA3B9CC), Color(0xFF2B3945))
            else SettingsIconSwatch(Color(0xFF526E85), Color(0xFFE6EEF4))
        SHORTCUTS ->
            if (dark) SettingsIconSwatch(Color(0xFFB8D68A), Color(0xFF34412D))
            else SettingsIconSwatch(Color(0xFF506D25), Color(0xFFEAF3D8))
        SHORTCUT_STYLE ->
            if (dark) SettingsIconSwatch(Color(0xFFE7B3A0), Color(0xFF49342F))
            else SettingsIconSwatch(Color(0xFF8F4C37), Color(0xFFF8E7DF))
        SHORTCUT_GALLERY ->
            if (dark) SettingsIconSwatch(Color(0xFF80D4DB), Color(0xFF23464B))
            else SettingsIconSwatch(Color(0xFF176D76), Color(0xFFE0F5F5))
        SHORTCUT_MUSIC ->
            if (dark) SettingsIconSwatch(Color(0xFFFFB17D), Color(0xFF513426))
            else SettingsIconSwatch(Color(0xFF9B5026), Color(0xFFFFE8D9))
        NEUTRAL, ABOUT ->
            if (dark) SettingsIconSwatch(Color(0xFFB2BBD1), Color(0xFF2A303E))
            else SettingsIconSwatch(Color(0xFF515C73), Color(0xFFE9ECF3))
    }
}
