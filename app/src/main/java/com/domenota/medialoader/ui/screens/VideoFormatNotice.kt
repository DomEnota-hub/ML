package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** New requests replace the label and cancel the previous hide timer, including repeated labels. */
@Composable
internal fun VideoFormatNotice(message: String?, requestId: Long, modifier: Modifier = Modifier) {
    var visible by remember(message, requestId) { mutableStateOf(message != null) }
    LaunchedEffect(message, requestId) {
        if (message != null) { delay(1_200L); visible = false }
    }
    if (visible && message != null) Text(message, color = Color.White,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.background(Color.Black.copy(alpha = 0.7f), MaterialTheme.shapes.medium)
            .padding(horizontal = 16.dp, vertical = 10.dp))
}
