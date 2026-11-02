package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.data.editor.VideoProjectAspect

@Composable
internal fun VideoProjectAspectPanel(selected: VideoProjectAspect, enabled: Boolean,
    onSelect: (VideoProjectAspect) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VideoProjectAspect.entries.forEach { aspect ->
            val active = selected == aspect
            Surface(onClick = { onSelect(aspect) }, enabled = enabled, shape = RoundedCornerShape(14.dp),
                color = if (active) videoToolYellow else Color(0xFF2B2B2B),
                modifier = Modifier.width(80.dp).height(72.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center) {
                    val tint = if (active) Color.Black else Color(0xFFECECEC)
                    if (aspect.ratio == null) Icon(VideoV8Icons.aspect, null, tint = tint, modifier = Modifier.size(24.dp))
                    else Canvas(Modifier.size(24.dp)) {
                        val r = requireNotNull(aspect.ratio)
                        val w = if (r >= 1) size.width * .75f else size.width * .75f * r
                        val h = if (r >= 1) size.height * .75f / r else size.height * .75f
                        drawRoundRect(tint, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h),
                            CornerRadius(2.dp.toPx()), style = Stroke(1.7.dp.toPx()))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(aspect.label, color = tint, fontSize = 12.sp, maxLines = 1)
                }
            }
        }
    }
}
