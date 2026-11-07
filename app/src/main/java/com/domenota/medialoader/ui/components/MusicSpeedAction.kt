package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

internal val musicSpeedChoices = listOf(.75f, 1f, 1.25f, 1.5f, 2f)

/** A compact control among other player actions, with no detached speed slider. */
@Composable
internal fun MusicSpeedAction(
    currentSpeed: Float,
    enabled: Boolean,
    onSpeedChange: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Rounded.Speed,
                contentDescription = "Скорость воспроизведения: ${currentSpeed}×",
                tint = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            musicSpeedChoices.forEach { choice ->
                val selected = kotlin.math.abs(currentSpeed - choice) < .001f
                DropdownMenuItem(
                    text = {
                        Text("${if (choice == 1f) "1" else choice.toString()}×",
                            color = MaterialTheme.colorScheme.onSurface)
                    },
                    onClick = {
                        onSpeedChange(choice)
                        open = false
                    },
                    trailingIcon = if (selected) ({
                        Icon(Icons.Rounded.Check, contentDescription = null,
                            tint = editorSliderYellow)
                    }) else null,
                )
            }
        }
    }
}
