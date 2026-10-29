package com.domenota.medialoader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun UiStateMessage(
    title: String,
    description: String
) {
    Text(
        text = "$title\n$description",
        style = MaterialTheme.typography.bodyLarge
    )
}
