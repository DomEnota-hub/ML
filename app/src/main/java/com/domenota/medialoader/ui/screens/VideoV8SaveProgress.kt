package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Approved HTML v8 export sheet: dim whole editor and bottom dark rounded
 * progress surface with actual work percentage. No simulated timer.
 * Cancel stays available so background export can be stopped safely.
 */
@Composable
internal fun VideoV8SaveProgress(
    operation: String,
    progress: Int,
    onCancel: () -> Unit,
) {
    val safeProgress = progress.coerceIn(0, 100)
    Dialog(onDismissRequest = {},
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .68f)),
            contentAlignment = Alignment.BottomCenter) {
            Surface(
                shape = RoundedCornerShape(34.dp),
                color = Color(0xFF242424),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)
                    .padding(bottom = 55.dp),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (operation.isBlank()) "Сохранение видео…" else operation,
                            color = Color.White, fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text("${safeProgress}%",
                            color = Color(0xFF448AFF), fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { safeProgress / 100f },
                        modifier = Modifier.fillMaxWidth().height(6.dp)
                            .clip(RoundedCornerShape(5.dp)),
                        color = Color(0xFF448AFF),
                        trackColor = Color(0xFF505050),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = onCancel) {
                            Text("Отменить", color = Color(0xFFAAB9D4), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
