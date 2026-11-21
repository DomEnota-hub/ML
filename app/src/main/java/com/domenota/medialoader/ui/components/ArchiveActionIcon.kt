package com.domenota.medialoader.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.R

/** Ordinary navigation glyph. Inherits surrounding content color in every theme. */
@Composable
internal fun ArchiveActionIcon(
    contentDescription: String? = "Архив",
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(
        painter = painterResource(R.drawable.ic_simple_archive),
        contentDescription = contentDescription,
        modifier = modifier.size(23.dp),
        tint = tint,
    )
}
