package com.domenota.medialoader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.editor.EditorFonts

/** In-panel font cards; shared by image, clip and text watermark controls. */
@Composable
internal fun EditorFontPicker(selectedId: String, onSelect: (String) -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val families = remember { EditorFonts.all.groupBy { it.id.substringBefore('-') }.toList() }
    val accent = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        families.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                pair.forEach { (family, variants) ->
                    val current = variants.firstOrNull { it.id == selectedId }
                    val font = current ?: variants.first()
                    val isOpen = family in expanded
                    Column(Modifier.weight(1f)
                        .background(if (current != null) accent.copy(alpha = .18f)
                            else MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(16.dp))) {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text((if (current != null) "✓ " else "") +
                                font.label.substringBefore(" ·"),
                                fontFamily = FontFamily(EditorFonts.face(context, font.id)),
                                modifier = Modifier.weight(1f).clickable { onSelect(font.id) }
                                    .padding(11.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2)
                            if (variants.size > 1) IconButton(onClick = {
                                expanded = if (isOpen) expanded - family else expanded + family
                            }) {
                                Icon(if (isOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = if (isOpen) "Свернуть " + family
                                        else "Начертания " + family,
                                    modifier = Modifier.size(18.dp))
                            }
                        }
                        if (isOpen) variants.forEach { item ->
                            Text((if (item.id == selectedId) "✓ " else "") +
                                item.label.substringAfter(" · ", "Обычный"),
                                fontFamily = FontFamily(EditorFonts.face(context, item.id)),
                                modifier = Modifier.fillMaxWidth().clickable { onSelect(item.id) }
                                    .padding(horizontal = 11.dp, vertical = 7.dp),
                                color = if (item.id == selectedId) accent
                                    else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
