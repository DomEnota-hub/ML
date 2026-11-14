package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.VideoReferenceAdjustment
import com.domenota.medialoader.data.editor.VideoReferenceAdjustments
import com.domenota.medialoader.ui.components.EditorSlider
import kotlin.math.roundToInt

/** One slider and thirteen original SVG tiles, matching HTML v8's adjust-panel. */
@Composable
internal fun VideoReferenceAdjustmentsPanel(settings: VideoReferenceAdjustments,
    enabled: Boolean, onChange: (VideoReferenceAdjustments) -> Unit) {
    var selected by rememberSaveable { mutableStateOf(VideoReferenceAdjustment.EXPOSURE) }
    val scroll = rememberLazyListState()
    LaunchedEffect(selected) { scroll.animateScrollToItem((selected.ordinal - 2).coerceAtLeast(0)) }
    val current = settings.value(selected)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(selected.label, color = Color(0xFFECECEC), fontSize = 12.sp)
            Text(current.roundToInt().toString(), color = videoToolYellow, fontSize = 12.sp)
        }
        EditorSlider(value = current, onValueChange = { onChange(settings.changed(selected, it)) },
            valueRange = selected.minimum..100f, enabled = enabled,
            modifier = Modifier.fillMaxWidth().height(48.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            listOf("Тон", "Цвет", "Детализация").forEach { group ->
                TextButton(onClick = { selected = VideoReferenceAdjustment.entries.first { it.group == group } },
                    enabled = enabled, modifier = Modifier.height(34.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(group, color = if (selected.group == group) videoToolYellow else Color.LightGray, fontSize = 12.sp)
                }
            }
        }
        LazyRow(state = scroll, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().height(92.dp)) {
            items(VideoReferenceAdjustment.entries, key = { it.name }) { tool ->
                val active = selected == tool
                TextButton(onClick = { selected = tool }, enabled = enabled,
                    modifier = Modifier.width(76.dp).height(90.dp), contentPadding = PaddingValues(0.dp)) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Surface(color = if (active) videoToolYellow else Color(0xFF2B2B2B),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) {
                            Box(Modifier.size(43.dp), contentAlignment = Alignment.Center) {
                                Icon(requireNotNull(VideoV8Icons.adjustments[tool.svgKey]), contentDescription = tool.label,
                                    tint = if (active) Color.Black else Color(0xFFECECEC), modifier = Modifier.size(24.dp))
                            }
                        }
                        Text(tool.label, color = if (active) videoToolYellow else Color(0xFFCCCCCC),
                            fontSize = 10.sp, lineHeight = 13.sp, textAlign = TextAlign.Center,
                            maxLines = 2, modifier = Modifier.fillMaxWidth().heightIn(min = 28.dp))
                    }
                }
            }
        }
    }
}
