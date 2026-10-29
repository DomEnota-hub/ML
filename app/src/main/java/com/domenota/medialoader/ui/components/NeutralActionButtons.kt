package com.domenota.medialoader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton as MaterialOutlinedButton
import androidx.compose.material3.TextButton as MaterialTextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Ordinary actions use readable theme text; selected and primary actions keep the accent. */
@Composable
fun NeutralTextButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: ButtonColors? = null,
    content: @Composable RowScope.() -> Unit) {
    MaterialTextButton(onClick = onClick, modifier = modifier, enabled = enabled,
        colors = colors ?: ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
        content = content)
}

@Composable
fun NeutralOutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, colors: ButtonColors? = null, border: BorderStroke? = null,
    content: @Composable RowScope.() -> Unit) {
    val light = MaterialTheme.colorScheme.background.luminance() > 0.5f
    MaterialOutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled,
        colors = colors ?: ButtonDefaults.outlinedButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)),
        border = border ?: BorderStroke(1.dp,
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) { if (light) 0.7f else 0.55f } else 0.25f)),
        content = content)
}
