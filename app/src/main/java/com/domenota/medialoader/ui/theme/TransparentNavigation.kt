package com.domenota.medialoader.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color
import android.os.Build
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Removes Android's three-button contrast scrim; layout insets remain the UI's responsibility. */
@Suppress("DEPRECATION")
internal fun transparentNavigation(window: Window, dark: Boolean? = null) {
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 28) window.navigationBarDividerColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    if (dark != null) WindowInsetsControllerCompat(window, window.decorView)
        .isAppearanceLightNavigationBars = !dark
}

internal fun edgeToEdgeWindow(window: Window, dark: Boolean? = null) {
    WindowCompat.setDecorFitsSystemWindows(window, false)
    transparentNavigation(window, dark)
}

internal fun appThemeIsDark(context: Context): Boolean {
    val prefs = context.getSharedPreferences("ui", 0)
    val mode = prefs.getString("theme", null)
        ?: if (prefs.contains("dark")) { if (prefs.getBoolean("dark", false)) "dark" else "light" } else "light"
    return when (mode) {
        "dark" -> true
        "system" -> context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        else -> false
    }
}

/** Also used inside separate Compose dialog/bottom-sheet windows. */
@Composable
internal fun TransparentWindowNavigation() {
    val view = LocalView.current
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val window = (view.parent as? DialogWindowProvider)?.window ?: activity(view.context)?.window
    SideEffect { window?.let { transparentNavigation(it, dark) } }
}

private tailrec fun activity(context: Context): Activity? = when (context) {
    is Activity -> context
    is ContextWrapper -> activity(context.baseContext)
    else -> null
}
