package com.domenota.medialoader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics

/** Hide temporarily unvalidated metadata, retaining ordinary remember state of active editors. */
@Composable
internal fun LibraryRefreshShield(loading: Boolean, modifier: Modifier = Modifier, onExit: (() -> Unit)? = null,
    content: @Composable () -> Unit) {
    BackHandler(enabled = loading && onExit != null) { onExit?.invoke() }
    Box(modifier.fillMaxSize()) {
        Box(if (loading) Modifier.clearAndSetSemantics { } else Modifier) { content() }
        if (loading) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CircularProgressIndicator()
                    Text("Проверяем файлы", color = MaterialTheme.colorScheme.onSurface)
                    if (onExit != null) TextButton(onClick = onExit) { Text("Вернуться в загрузчик") }
                }
            }
    }
}
