package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player

/** Controls the session player itself so background and mini-player navigation agree. */
@Composable
internal fun AudioPlaybackModes(player: Player, repeatMode: Int, shuffleEnabled: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconButton(onClick = {
                player.repeatMode = when (player.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
            }) {
                Icon(if (repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = repeatDescription(repeatMode),
                    tint = if (repeatMode == Player.REPEAT_MODE_OFF) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.primary)
            }
            IconToggleButton(checked = shuffleEnabled,
                onCheckedChange = { player.shuffleModeEnabled = it }) {
                Icon(Icons.Rounded.Shuffle, contentDescription = "Перемешать",
                    tint = if (shuffleEnabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(repeatDescription(repeatMode), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun repeatDescription(mode: Int): String = when (mode) {
    Player.REPEAT_MODE_ALL -> "Повтор очереди"
    Player.REPEAT_MODE_ONE -> "Повтор трека"
    else -> "Повтор выключен"
}
