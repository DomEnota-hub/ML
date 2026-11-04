package com.domenota.medialoader.ui.screens

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 24x24 editor contours. Video actions are aligned with Drive reference 1.jpg/2.jpg;
 * unrelated icons retain the approved v8 contours. Rounded strokes share one weight.
 */
internal object VideoV8Icons {
    internal fun fromSvg(name: String, data: String, solidData: String = ""): ImageVector =
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
            if (solidData.isNotEmpty()) addPath(
                pathData = PathParser().parsePathString(solidData).toNodes(),
                fill = SolidColor(Color.White),
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
    val trash = fromSvg("trash",
        "M3 6h18M8 6l2-3h4l2 3M5 6l1 14a2 2 0 0 0 2 2h8a2 2 0 0 0 2-2l1-14M10 10v7M14 10v7")
    val effects = fromSvg("effects", "M12 2l2.4 6.6L21 11l-6.6 2.4L12 20l-2.4-6.6L3 11l6.6-2.4Z M20 2v4m-2-2h4")
    val separate = fromSvg("separate",
        "M11 21H6a4 4 0 0 1-4-4V6a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4v5M17 20v-6h5", "M8 7v9l6-4.5Z M17 18a3 3 0 1 0 0 4Z")
    val replace = fromSvg("replace",
        "M2 12V8a6 6 0 0 1 6-6h10a4 4 0 0 1 3 1M22 12v4a6 6 0 0 1-6 6H6a4 4 0 0 1-3-1", "M2 12V7l5 0Z M22 12v5h-5Z")
    val trim = fromSvg("trim",
        "M3 2h2a1 1 0 0 1 1 1v18a1 1 0 0 1-1 1H3M21 2h-2a1 1 0 0 0-1 1v18a1 1 0 0 0 1 1h2M11 2v20M13 2v20")
    val speed = fromSvg("speed",
        "M9 2.5a10 10 0 1 0 9 1M12 12l7-7")
    val volume = fromSvg("volume",
        "M3 8h5l7-5v18l-7-5H3a2 2 0 0 1-2-2v-4a2 2 0 0 1 2-2ZM19 6a8 8 0 0 1 0 12")
    val reverse = fromSvg("reverse",
        "M2 10V7a5 5 0 0 1 5-5h10a5 5 0 0 1 5 5v10a5 5 0 0 1-5 5H7a5 5 0 0 1-5-5", "M10 8v8l6-4Z")
    val crop = fromSvg("crop",
        "M4 1v15a4 4 0 0 0 4 4h15M1 4h15a4 4 0 0 1 4 4v15")
    val copy = fromSvg("copy",
        "M9 4V3a2 2 0 0 1 2-2h8a4 4 0 0 1 4 4v9a3 3 0 0 1-1 2M5 7h9a4 4 0 0 1 4 4v9a3 3 0 0 1-3 3H5a4 4 0 0 1-4-4v-8a4 4 0 0 1 4-4Z")
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
