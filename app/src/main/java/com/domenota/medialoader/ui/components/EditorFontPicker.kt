package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.EditorFonts

@Composable
internal fun EditorFontPicker(selectedId: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val families = remember { EditorFonts.all.groupBy { it.id.substringBefore('-') } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        families.forEach { (family, variants) ->
            val selected = variants.firstOrNull { it.id == selectedId }
            val sample = selected ?: variants.first()
            val open = family in expanded
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onSelect(sample.id) }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (selected != null)
                        MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)) {
                    Text((if (selected != null) "✓ " else "") + sample.label,
                        fontFamily = FontFamily(EditorFonts.face(context, sample.id)))
                }
                if (variants.size > 1) OutlinedIconButton(onClick = {
                    expanded = if (open) expanded - family else expanded + family
                }) {
                    Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = (if (open) "Свернуть " else "Начертания ") + sample.label.substringBefore(" ·"))
                }
            }
            if (open) Column(Modifier.padding(start = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                variants.forEach { font ->
                    OutlinedButton(onClick = { onSelect(font.id) }, modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (font.id == selectedId)
                            MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
                        Text((if (font.id == selectedId) "✓ " else "") +
                            (font.label.substringAfter(" · ", "Обычный")),
                            fontFamily = FontFamily(EditorFonts.face(context, font.id)))
                    }
                }
            }
        }
    }
}
