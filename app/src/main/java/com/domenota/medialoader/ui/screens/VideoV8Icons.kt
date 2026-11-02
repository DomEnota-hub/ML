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
        "m7 7 13 13M7 17 20 4M8 5a3 3 0 1 1-6 0 3 3 0 0 1 6 0M8 19a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
    val music = fromSvg("music",
        "M9 18V4l11-2v14M9 8l11-2M9 18a3 3 0 1 1-6 0 3 3 0 0 1 6 0M20 16a3 3 0 1 1-6 0 3 3 0 0 1 6 0")
    val text = fromSvg("text", "M4 4h16v16H4zM8 8h8m-4 0v9")
    val aspect = fromSvg("aspect", "M3 3h18v18H3zM3 14h8v7")
    val adjustment = fromSvg("adjust",
        "M3 5h18M3 12h18M3 19h18M8 2v6M16 9v6M10 16v6")
    val filters = fromSvg("filters",
        "M10 8a4 4 0 1 1 8 0 4 4 0 0 1-8 0M4 17a4 4 0 1 1 8 0 4 4 0 0 1-8 0M13 17a4 4 0 1 1 8 0 4 4 0 0 1-8 0")
    val more = fromSvg("more", "M12 3.9v.2M12 11.9v.2M12 19.9v.2")
    val trim = fromSvg("trim", "M7 3h3v18H7M17 3h-3v18h3")
    val speed = fromSvg("speed", "M20 6a10 10 0 1 1-5-4M12 13l7-10")
    val volume = fromSvg("volume", "M3 8h5l6-5v18l-6-5H3zm15-3v14")
    val reverse = fromSvg("reverse",
        "M5 5h13a3 3 0 0 1 3 3v11H7a4 4 0 0 1-4-4v-2M3 8h11m-4-4 4 4-4 4")
    val crop = fromSvg("crop", "M6 2v16h16M2 6h16v16")
    val copy = fromSvg("copy", "M8 3h13v14H8zM3 8v13h13")
    val addText = fromSvg("add_text", "M3 5h14v14H3zM6 9h8m-4 0v7M20 12v9m-4-5h8")
}
