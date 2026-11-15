package com.domenota.medialoader.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun outlineIcon(name: String, drawing: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name,24.dp,24.dp,24f,24f).apply {
        path(stroke=SolidColor(Color.Black),strokeLineWidth=1.65f,
            strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round) { drawing() }
    }.build()

internal val MediaEditOutline = outlineIcon("MediaEditOutline") {
    moveTo(4f,16.8f); lineTo(15.8f,5f); curveTo(16.8f,4f,18.2f,4f,19.2f,5f)
    lineTo(20f,5.8f); curveTo(21f,6.8f,21f,8.2f,20f,9.2f)
    lineTo(8.2f,21f); lineTo(3f,21.8f); close()
    moveTo(13.8f,7f); lineTo(18f,11.2f)
    moveTo(4f,16.8f); lineTo(8.2f,21f)
}

internal val ManualHandOutline = outlineIcon("ManualHandOutline") {
    moveTo(8f,13f); lineTo(8f,6f); curveTo(8f,4f,11f,4f,11f,6f)
    lineTo(11f,11f); lineTo(11f,4f); curveTo(11f,2f,14f,2f,14f,4f)
    lineTo(14f,11f); lineTo(14f,6f); curveTo(14f,4f,17f,4f,17f,6f)
    lineTo(17f,12f); lineTo(17f,9f); curveTo(17f,7f,20f,7f,20f,9f)
    lineTo(20f,15.5f); curveTo(20f,19.5f,17.3f,22f,13.5f,22f)
    lineTo(12.5f,22f); curveTo(10.6f,22f,9.1f,21f,7.9f,19.5f)
    lineTo(3.7f,15f); curveTo(2.3f,13.4f,4.4f,11.3f,6.3f,12.6f)
    lineTo(8f,14f)
}

internal val CalendarDatesOutline = ImageVector.Builder("CalendarDatesOutline",24.dp,24.dp,24f,24f).apply {
    path(stroke=SolidColor(Color.Black),strokeLineWidth=1.55f,strokeLineCap=StrokeCap.Round,strokeLineJoin=StrokeJoin.Round) {
        moveTo(5.5f,4.5f); lineTo(18.5f,4.5f); curveTo(20f,4.5f,21f,5.5f,21f,7f)
        lineTo(21f,19f); curveTo(21f,20.5f,20f,21.5f,18.5f,21.5f); lineTo(5.5f,21.5f)
        curveTo(4f,21.5f,3f,20.5f,3f,19f); lineTo(3f,7f); curveTo(3f,5.5f,4f,4.5f,5.5f,4.5f); close()
        moveTo(8f,2.5f); lineTo(8f,6.5f); moveTo(16f,2.5f); lineTo(16f,6.5f)
        moveTo(3f,9f); lineTo(21f,9f)
    }
    // Nine equally sized rounded date marks; no highlighted date.
    path(fill=SolidColor(Color.Black)) {
        for (row in 0..2) for (column in 0..2) {
            val x=6.7f+column*4.5f; val y=11.1f+row*3.1f
            moveTo(x+.35f,y); lineTo(x+1.85f,y); curveTo(x+2.2f,y,x+2.2f,y+.2f,x+2.2f,y+.35f)
            lineTo(x+2.2f,y+1.85f); curveTo(x+2.2f,y+2.2f,x+2f,y+2.2f,x+1.85f,y+2.2f)
            lineTo(x+.35f,y+2.2f); curveTo(x,y+2.2f,x,y+2f,x,y+1.85f)
            lineTo(x,y+.35f); curveTo(x,y,x+.2f,y,x+.35f,y); close()
        }
    }
}.build()

internal val CollectionNotesOutline = outlineIcon("CollectionNotesOutline") {
    moveTo(8.5f,17.5f); lineTo(8.5f,5.8f); lineTo(20f,3.5f); lineTo(20f,15.4f)
    moveTo(8.5f,9f); lineTo(20f,6.7f)
    moveTo(8.5f,17.5f); curveTo(8.5f,20.3f,2.5f,21.1f,2.5f,18.1f)
    curveTo(2.5f,15.5f,8.5f,14.7f,8.5f,17.5f)
    moveTo(20f,15.4f); curveTo(20f,18.2f,14f,19f,14f,16f)
    curveTo(14f,13.4f,20f,12.6f,20f,15.4f)
}
