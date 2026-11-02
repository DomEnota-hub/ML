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
    val undo = fromSvg("undo", "M9 5 3 10l6 5M3 10h10c6 0 8 3 8 9")
    val redo = fromSvg("redo", "m15 5 6 5-6 5m6-5H11c-6 0-8 3-8 9")
    val trash = fromSvg("trash", "M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7")
    val separate = fromSvg("separate", "M4 3h15v12H4zm6 5 4 3-4 2zM13 20v-5l7-2v5m-7 2a2 2 0 1 1-4 0 2 2 0 0 1 4 0")
    val replace = fromSvg("replace", "M4 7V3h16v14M20 17v4H4V7M1 7l3-3 3 3m10 10 3 3 3-3")
    val trim = fromSvg("trim", "M3 3h3v18H3M21 3h-3v18h3M12 2v20")
    val speed = fromSvg("speed", "M15 2.5a10 10 0 1 0 5 3.5M12 12l7-7")
    val volume = fromSvg("volume", "M3 8h6l7-6v20l-7-6H3a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1ZM20 6a9 9 0 0 1 0 12")
    val reverse = fromSvg("reverse",
        "M2 11V7a5 5 0 0 1 5-5h10a5 5 0 0 1 5 5v10a5 5 0 0 1-5 5H7a5 5 0 0 1-5-5M10 8l6 4-6 4Z")
    val crop = fromSvg("crop", "M4 2v15a3 3 0 0 0 3 3h15M2 5h15a3 3 0 0 1 3 3v14")
    val copy = fromSvg("copy", "M8 3h13v14H8zM3 8v13h13")
    val addText = fromSvg("add_text", "M3 5h14v14H3zM6 9h8m-4 0v7M20 12v9m-4-5h8")
    val musicImport = fromSvg("music_import", "M3 6h7l2 2h9v12H3zM12 11v6m-3-3h6")
    val adjustments = mapOf(
        "exposure" to fromSvg("exposure", "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20M8 9h8m-4-4v8m-4 4h8"),
        "brightness" to fromSvg("brightness", "M12 1v3m0 16v3M1 12h3m16 0h3M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2M17 12a5 5 0 1 1-10 0 5 5 0 0 1 10 0"),
        "contrast" to fromSvg("contrast", "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20M12 2v20M12 4c9 0 9 16 0 16"),
        "highlights" to fromSvg("highlights", "M17 4a9 9 0 1 0 3 3M12 6a6 6 0 0 1 6 6"),
        "shadows" to fromSvg("shadows", "M6 3a10 10 0 1 1-3 3M6 3a9 9 0 0 1-3 16M6 3c11 0 16 16-3 16"),
        "saturation" to fromSvg("saturation", "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20M12 2v20M7 4v16M17 4v16"),
        "vibrance" to fromSvg("vibrance", "M3 5v14M8 2v20M13 4v16M18 6v12M22 9v6"),
        "warmth" to fromSvg("warmth", "M9 14V5a3 3 0 0 1 6 0v9a5 5 0 1 1-6 0m3-8v11"),
        "tint" to fromSvg("tint", "M12 2S3 12 3 16a9 9 0 0 0 18 0c0-4-9-14-9-14M4 16c4-6 12 6 16 0"),
        "black" to fromSvg("black", "M3 10h18M3 14h18M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20"),
        "sharp" to fromSvg("sharp", "m12 2 10 19H2z"),
        "grain" to fromSvg("grain", "M12 2a10 10 0 1 1 0 20 10 10 0 0 1 0-20M7 8h.01m5-2h.01m5 2h.01M6 13h.01m5-2h.01m6 2h.01M8 17h.01m5-2h.01m3 3h.01"),
        "vignette" to fromSvg("vignette", "M4 3h16v18H4zM12 4c9 1 9 16 0 16-9-1-9-15 0-16")
    )
}
