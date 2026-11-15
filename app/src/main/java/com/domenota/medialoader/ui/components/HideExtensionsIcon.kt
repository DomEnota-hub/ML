package com.domenota.medialoader.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

internal val HideExtensionsIcon: ImageVector = ImageVector.Builder(
    name = "HideExtensions", defaultWidth = 24.dp, defaultHeight = 24.dp,
    viewportWidth = 36f, viewportHeight = 36f,
).apply {
    path(stroke = SolidColor(Color.Black), strokeLineWidth = 2.6f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) {
        moveTo(4f, 25f); lineTo(11f, 9f); lineTo(18f, 25f)
        moveTo(7f, 20f); lineTo(15f, 20f)
        moveTo(22f, 23f); lineTo(22f, 25f)
        moveTo(26f, 16f); lineTo(33f, 16f)
        moveTo(26f, 20f); lineTo(33f, 20f)
        moveTo(26f, 24f); lineTo(33f, 24f)
        moveTo(23f, 28f); lineTo(34f, 12f)
    }
}.build()
