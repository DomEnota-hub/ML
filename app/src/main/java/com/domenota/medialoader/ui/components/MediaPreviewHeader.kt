package com.domenota.medialoader.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
fun MediaPreviewHeader(
    title: String = "Предпросмотр",
    subtitle: String = "Выберите файлы для загрузки"
) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(
        subtitle,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
