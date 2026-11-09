package com.domenota.medialoader.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * The user-provided editor video (1000035981.mp4) shows a stationary white
 * rounded square and a triangle that turns around its vertical axis:
 * black face -> narrow shaded side facet -> white outlined face -> reverse.
 *
 * Importantly, this is a perspective-width morph, NOT a flat 2D spin,
 * which would resemble the navigation icon that QA rejected.
 */
internal object AiPrismMotion {
    internal data class Frame(
        val faceWidth: Float,
        val sideWidth: Float,
        val faceIsWhite: Boolean,
        val sideOnRight: Boolean,
    )

    fun frame(degrees: Float): Frame {
        val radians = Math.toRadians(degrees.toDouble())
        val projected = cos(radians).toFloat()
        val side = sin(radians).toFloat()
        return Frame(
            faceWidth = abs(projected).coerceAtLeast(0.045f),
            sideWidth = abs(side) * 0.28f,
            faceIsWhite = projected < 0f,
            sideOnRight = side >= 0f,
        )
    }
}

@Composable
internal fun AiPrismWorkingIndicator(
    label: String,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "ai-prism-working")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 180f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "prism-perspective-turn",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Canvas(
            Modifier.size(108.dp).background(
                Color(0xFFF9F9F9), RoundedCornerShape(24.dp),
            ),
        ) {
            val w = size.width
            val h = size.height
            val px = w / 108f
            // A subtle stationary diagonal surface highlight from the video.
            val highlight = Path().apply {
                moveTo(w * .63f, 0f)
                lineTo(w * .77f, 0f)
                lineTo(w * .19f, h)
                lineTo(w * .05f, h)
                close()
            }
            drawPath(highlight, Color.White.copy(alpha = .48f))

            val frame = AiPrismMotion.frame(phase)
            val cx = w * .50f
            val cy = h * .51f
            val extent = 17f * px * frame.faceWidth
            val sideShift = 17f * px * frame.sideWidth
            val top = Offset(cx, cy - 15f * px)
            val left = Offset(cx - extent, cy + 14f * px)
            val right = Offset(cx + extent, cy + 14f * px)
            val face = Path().apply {
                moveTo(top.x, top.y)
                lineTo(right.x, right.y)
                lineTo(left.x, left.y)
                close()
            }
            val edge = Offset(
                cx + (if (frame.sideOnRight) sideShift else -sideShift),
                cy + 13f * px,
            )
            val sideFace = Path().apply {
                moveTo(top.x, top.y)
                lineTo(edge.x, edge.y)
                lineTo(right.x, right.y)
                close()
            }
            drawPath(face, if (frame.faceIsWhite) Color.White else Color.Black)
            drawPath(
                sideFace,
                if (frame.faceIsWhite) Color(0xFFE9E9E9) else Color(0xFF3C3C3C),
            )
            drawPath(face, Color.Black, style = Stroke(width = 1.55f * px))
            if (frame.sideWidth > 0.05f) {
                val ridge = Path().apply {
                    moveTo(top.x, top.y)
                    lineTo(edge.x, edge.y)
                }
                drawPath(ridge, Color.Black, style = Stroke(width = 1.15f * px))
            }
        }
        Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}
