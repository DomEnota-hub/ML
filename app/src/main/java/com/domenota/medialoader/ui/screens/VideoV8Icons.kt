package com.domenota.medialoader.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * EXACT 24x24 contours from approved HTML v8's referenceIconShapes, not generic
 * Material icons. Stroke, cap and join match the <svg> stylesheet in the mockup.
 */
internal object VideoV8Icons {
    private fun fromSvg(name: String, data: String): ImageVector =
        ImageVector.Builder(
            name = "video_v8_$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.White),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val editor = fromSvg("scissors",
        "M8.5 19a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0 M22.5 19a3.5 3.5 0 1 1-7 0a3.5 3.5 0 1 1 7 0 M7 16 18 2M17 16 6 2")
    val music = fromSvg("music",
        "M10 18V5a2 2 0 0 1 2-2h8v2a3 3 0 0 1-3 3h-7 M10 18a4 4.5 0 1 1-8 0a4 4.5 0 1 1 8 0")
    val text = fromSvg("text",
        "M6 2H18A4 4 0 0 1 22 6V18A4 4 0 0 1 18 22H6A4 4 0 0 1 2 18V6A4 4 0 0 1 6 2Z M7 8h10m-5 0v9")
    val aspect = fromSvg("aspect",
        "M6 2H18A4 4 0 0 1 22 6V18A4 4 0 0 1 18 22H6A4 4 0 0 1 2 18V6A4 4 0 0 1 6 2Z M2 12h7a3 3 0 0 1 3 3v7")
    val adjustment = fromSvg("adjust",
        "M2 6h11m6 0h3M2 18h3m6 0h11M19 6a3 3 0 1 1-6 0a3 3 0 1 1 6 0M11 18a3 3 0 1 1-6 0a3 3 0 1 1 6 0")
    val filters = fromSvg("filters",
        "M19 6.5a5 5 0 1 1-10 0a5 5 0 1 1 10 0M6.4 8.9a5 5 0 1 0 6.2 7.6M16.9 12.1a5 5 0 1 1-2.4 7.7")
    val more = fromSvg("more", "M12 3.9v.2M12 11.9v.2M12 19.9v.2")
    val trim = fromSvg("trim", "M3 3h3v18H3M21 3h-3v18h3M12 2v20")
    val speed = fromSvg("speed", "M15 2.5a10 10 0 1 0 5 3.5M12 12l7-7")
    val volume = fromSvg("volume", "M3 8h6l7-6v20l-7-6H3a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1ZM20 6a9 9 0 0 1 0 12")
    val reverse = fromSvg("reverse",
        "M2 11V7a5 5 0 0 1 5-5h10a5 5 0 0 1 5 5v10a5 5 0 0 1-5 5H7a5 5 0 0 1-5-5M10 8l6 4-6 4Z")
    val crop = fromSvg("crop", "M4 2v15a3 3 0 0 0 3 3h15M2 5h15a3 3 0 0 1 3 3v14")
    val copy = fromSvg("copy", "M8 3h13v14H8zM3 8v13h13")
    val addText = fromSvg("add_text", "M3 5h14v14H3zM6 9h8m-4 0v7M20 12v9m-4-5h8")
}
