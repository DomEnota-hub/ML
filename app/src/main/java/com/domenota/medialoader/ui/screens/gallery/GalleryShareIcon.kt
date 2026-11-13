package com.domenota.medialoader.ui.screens.gallery

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke

/** Same upload/share silhouette, with optical weight matched to the other toolbar actions. */
@Composable
internal fun GalleryShareIcon(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val u = minOf(size.width,size.height) / 24f
        val x = (size.width - 24f*u)/2f
        val y = (size.height - 24f*u)/2f
        fun point(a: Float,b: Float) = Offset(x+a*u,y+b*u)
        val outline = Path().apply {
            moveTo(x+8*u,y+9*u)
            lineTo(x+5.6f*u,y+9*u)
            quadraticBezierTo(x+3.8f*u,y+9*u,x+3.8f*u,y+10.8f*u)
            lineTo(x+3.8f*u,y+20*u)
            quadraticBezierTo(x+3.8f*u,y+21.8f*u,x+5.6f*u,y+21.8f*u)
            lineTo(x+18.4f*u,y+21.8f*u)
            quadraticBezierTo(x+20.2f*u,y+21.8f*u,x+20.2f*u,y+20*u)
            lineTo(x+20.2f*u,y+10.8f*u)
            quadraticBezierTo(x+20.2f*u,y+9*u,x+18.4f*u,y+9*u)
            lineTo(x+16*u,y+9*u)
        }
        val stroke = 1.55f*u
        drawPath(outline,Color.White,style=Stroke(stroke,cap=StrokeCap.Round,join=StrokeJoin.Round))
        drawLine(Color.White,point(12f,2.2f),point(12f,15f),stroke,StrokeCap.Round)
        val arrow = Path().apply {
            moveTo(x+7.7f*u,y+6.6f*u); lineTo(x+12*u,y+2.2f*u); lineTo(x+16.3f*u,y+6.6f*u)
        }
        drawPath(arrow,Color.White,style=Stroke(stroke,cap=StrokeCap.Round,join=StrokeJoin.Round))
    }
}
