package com.domenota.medialoader.ui.components

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/** Full-width editor measured against its real window, not Configuration.screenHeightDp. */
@Composable
internal fun EditorWindow(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    // Compose 1.7.4 overrides the height MeasureSpec when usePlatformDefaultWidth=false.
    // Keep the normal measuring path and explicitly expand the Android window instead.
    Dialog(onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = true, decorFitsSystemWindows = true)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        val window = (LocalView.current.parent as DialogWindowProvider).window
        SideEffect {
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        }
        content()
    }
}
