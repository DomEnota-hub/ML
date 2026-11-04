package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun VideoReferencePanelFooter(title: String, enabled: Boolean,
    onCancel: () -> Unit, onApply: () -> Unit) {
    Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel, enabled = enabled) {
            Icon(Icons.Rounded.Close, "Отменить изменения раздела", tint = Color(0xFFECECEC))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Text(title, color = Color(0xFFECECEC), fontSize = 14.sp, maxLines = 1)
        }
        IconButton(onClick = onApply, enabled = enabled) {
            Icon(Icons.Rounded.Check, "Применить изменения", tint = videoToolYellow)
        }
    }
}
