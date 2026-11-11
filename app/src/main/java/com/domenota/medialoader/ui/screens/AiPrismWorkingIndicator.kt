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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 1000035981.mp4: a stationary rounded white tile and a *thick* triangular
 * prism with deliberate holds. Mid-turn has a visible rectangular side, not
 * a flat triangle collapsed to an almost invisible stroke.
 */
internal object AiPrismMotion {
    internal data class Frame(
        val faceWidth: Float,
        val sideWidth: Float,
        val faceIsWhite: Boolean,
        val sideOnRight: Boolean,
    )

    /** Progress is a repeating 0..1 timeline including front-face holds. */
    fun frame(progress: Float): Frame {
        val p = ((progress % 1f) + 1f) % 1f
        val (rotation, white, right) = when {
            // Reference sequence: BLACK front -> thick side -> WHITE outlined
            // front -> thick side -> BLACK front; tile never rotates.
            p < .19f -> Triple(0f, false, true)
            p < .30f -> Triple((p - .19f) / .11f, false, true)
            p < .40f -> Triple(1f - (p - .30f) / .10f, true, false)
            p < .60f -> Triple(0f, true, false)
            p < .70f -> Triple((p - .60f) / .10f, true, false)
            p < .81f -> Triple(1f - (p - .70f) / .11f, false, true)
            else -> Triple(0f, false, true)
        }
        val angle = rotation.coerceIn(0f, 1f) * (PI / 2.0)
        return Frame(
            faceWidth = cos(angle).toFloat().coerceIn(0f, 1f),
            sideWidth = sin(angle).toFloat() * .38f,
            faceIsWhite = white,
            sideOnRight = right,
        )
    }
}

@Composable
internal fun AiPrismWorkingIndicator(
    label: String,
    modifier: Modifier = Modifier,
    showLabel: Boolean = true,
    tileSize: androidx.compose.ui.unit.Dp = 108.dp,
) {
    val transition = rememberInfiniteTransition(label = "ai-prism-working")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3300, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "prism-thick-facet-cycle",
    )

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        val tile = RoundedCornerShape(24.dp)
        Canvas(
            Modifier.size(tileSize).clip(tile).background(Color(0xFFF9F9F9)),
        ) {
            val w = size.width
            val h = size.height
            val px = w / 108f
            // Clipping the *whole canvas* to the rounded square also removes
            // the stray grey strip at the bottom-right corner of the tile.
            val highlight = Path().apply {
                moveTo(w * .63f, 0f)
                lineTo(w * .77f, 0f)
                lineTo(w * .19f, h)
                lineTo(w * .05f, h)
                close()
            }
            drawPath(highlight, Color.White.copy(alpha = .48f))

            val state = AiPrismMotion.frame(phase)
            val cx = w * .50f
            val cy = h * .51f
            val topY = cy - 15f * px
            val bottomY = cy + 14f * px

            // A true side has finite breadth. At 90 degrees both top and
            // bottom span the same depth: a readable *rectangle*, not a needle.
            val depth = 17f * px * state.sideWidth
            val bottomHalf = 17f * px * state.faceWidth + depth
            val topHalf = depth
            val topLeft = Offset(cx - topHalf, topY)
            val topRight = Offset(cx + topHalf, topY)
            val bottomLeft = Offset(cx - bottomHalf, bottomY)
            val bottomRight = Offset(cx + bottomHalf, bottomY)

            val (ridgeTop, ridgeBottom) = if (state.sideOnRight) {
                Offset(cx + depth * .25f, topY) to
                    Offset(bottomRight.x - depth * 1.40f, bottomY)
            } else {
                Offset(cx - depth * .25f, topY) to
                    Offset(bottomLeft.x + depth * 1.40f, bottomY)
            }
            val mainFace = Path().apply {
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                if (state.sideOnRight) {
                    lineTo(ridgeTop.x, ridgeTop.y)
                    lineTo(ridgeBottom.x, ridgeBottom.y)
                    lineTo(bottomLeft.x, bottomLeft.y)
                } else {
                    lineTo(bottomRight.x, bottomRight.y)
                    lineTo(ridgeBottom.x, ridgeBottom.y)
                    lineTo(ridgeTop.x, ridgeTop.y)
                }
                close()
            }
            val sideFace = Path().apply {
                moveTo(ridgeTop.x, ridgeTop.y)
                lineTo(if (state.sideOnRight) topRight.x else topLeft.x, topY)
                lineTo(if (state.sideOnRight) bottomRight.x else bottomLeft.x, bottomY)
                lineTo(ridgeBottom.x, ridgeBottom.y)
                close()
            }
            val silhouette = Path().apply {
                moveTo(topLeft.x, topLeft.y)
                lineTo(topRight.x, topRight.y)
                lineTo(bottomRight.x, bottomRight.y)
                lineTo(bottomLeft.x, bottomLeft.y)
                close()
            }

            drawPath(silhouette,
                if (state.faceIsWhite) Color.White else Color.Black)
            drawPath(mainFace, if (state.faceIsWhite) Color.White else Color.Black)
            if (state.sideWidth > .001f) {
                drawPath(sideFace,
                    if (state.faceIsWhite) Color(0xFF3B3B3B) else Color(0xFFEDEDED))
                drawLine(
                    Color.Black, ridgeTop, ridgeBottom,
                    strokeWidth = 1.10f * px,
                )
            }
            drawPath(silhouette, Color.Black, style = Stroke(width = 1.55f * px))
        }
        if (showLabel && label.isNotBlank()) Text(
            text = label,
            color = Color.White,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}
