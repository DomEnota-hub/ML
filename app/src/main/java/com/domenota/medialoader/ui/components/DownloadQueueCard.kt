package com.domenota.medialoader.ui.components

import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun DownloadQueueCard(
    name: String,
    status: String,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
        Text(name)
        Text(status)
    }
}
