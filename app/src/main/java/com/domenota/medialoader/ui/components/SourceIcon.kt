package com.domenota.medialoader.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Shared source marks for the three screens. Drawn in-app so no third-party asset is bundled. */
@Composable
fun SourceIcon(providerId: String?, size: Dp = 42.dp, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.27f)
    val fill = when (providerId?.lowercase()) {
        "instagram" -> Brush.linearGradient(listOf(Color(0xFF7046E8), Color(0xFFD62E97), Color(0xFFFFA33B)))
        "youtube" -> Brush.linearGradient(listOf(Color(0xFFFF3341), Color(0xFFE6212E)))
        "vk" -> Brush.linearGradient(listOf(Color(0xFF278BFF), Color(0xFF0860D7)))
        "rutube" -> Brush.linearGradient(listOf(Color(0xFF24113F), Color(0xFF080713)))
        "hitmoz", "muzmo" -> Brush.linearGradient(listOf(Color(0xFF9E527A), Color(0xFF64304F)))
        else -> Brush.linearGradient(listOf(Color(0xFF8064CB), Color(0xFF6044A8)))
    }
    Box(modifier.size(size).background(fill, shape), contentAlignment = Alignment.Center) {
        when (providerId?.lowercase()) {
            "instagram" -> Canvas(Modifier.size(size * 0.55f)) {
                val line = this.size.width * 0.09f
                drawRoundRect(Color.White, cornerRadius = CornerRadius(this.size.width * 0.25f),
                    style = Stroke(line))
                drawCircle(Color.White, radius = this.size.width * 0.18f, style = Stroke(line))
                drawCircle(Color.White, radius = line * 0.58f,
                    center = Offset(this.size.width * 0.77f, this.size.height * 0.24f))
            }
            "youtube" -> Canvas(Modifier.size(size * 0.62f)) {
                val width = this.size.width
                val height = this.size.height
                val play = Path().apply {
                    moveTo(width * 0.32f, height * 0.18f)
                    lineTo(width * 0.78f, height * 0.5f)
                    lineTo(width * 0.32f, height * 0.82f)
                    close()
                }
                drawPath(play, Color.White)
            }
            "hitmoz", "muzmo" -> Text("♫", color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = (size.value * 0.55f).sp)
            "vk" -> Text("VK", color = Color.White, fontWeight = FontWeight.Black,
                fontSize = (size.value * 0.36f).sp)
            "rutube" -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("R", color = Color.White, fontWeight = FontWeight.Black,
                    fontSize = (size.value * 0.50f).sp)
                Canvas(Modifier.fillMaxSize()) {
                    drawCircle(
                        color = Color(0xFFFF214E),
                        radius = this.size.minDimension * 0.085f,
                        center = Offset(this.size.width * 0.73f, this.size.height * 0.27f),
                    )
                }
            }
            else -> Text("•", color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
