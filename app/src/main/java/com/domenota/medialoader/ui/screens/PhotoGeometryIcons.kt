package com.domenota.medialoader.ui.screens

/** Geometry symbols observed in Drive/Образцы/Сверка, with the editor's outline weight. */
internal object PhotoGeometryIcons {
    val horizon = VideoV8Icons.fromSvg("geometry_horizon",
        "M3 8a9 9 0 0 1 17-2M21 16a9 9 0 0 1-17 2M1 12h22M20 2v5h-5M4 22v-5h5")
    val horizontal = VideoV8Icons.fromSvg("geometry_horizontal",
        "M8 3l10-1 1 7M16 21l-10 1-1-7M2 9h20M2 15h20M6 9v6M18 9v6")
    val vertical = VideoV8Icons.fromSvg("geometry_vertical",
        "M9 4l-5 1-1 14 6-1M15 4l5 1 1 14-6-1M12 1v22")
    val distortion = VideoV8Icons.fromSvg("geometry_distortion",
        "M3 3Q12 6 21 3Q18 12 21 21Q12 18 3 21Q6 12 3 3Z")
    val free = VideoV8Icons.fromSvg("geometry_free",
        "M5 3h2m3 0h4m3 0h2M21 5v2m0 3v4m0 3v2M19 21h-2m-3 0h-4m-3 0H5M3 19v-2m0-3v-4m0-3V5",
        "M1 1h4v4H1Z M19 1h4v4h-4Z M1 19h4v4H1Z M19 19h4v4h-4Z")
}
