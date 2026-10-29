package com.domenota.medialoader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A circular indicator with a separate, accessible touch target. */
@Composable
fun MediaSelectionMark(
    selected: Boolean,
    onToggle: (() -> Unit)? = null,
    description: String? = null,
    atThumbnailCorner: Boolean = false,
) {
    val indicator: @Composable () -> Unit = {
        Box(Modifier.size(26.dp)
            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.55f), CircleShape)
            .then(if (selected) Modifier else Modifier.border(1.dp, Color.White.copy(alpha = 0.8f), CircleShape)),
            contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null,
                tint = Color.White, modifier = Modifier.size(19.dp))
        }
    }
    if (onToggle == null) {
        indicator()
    } else {
        Box(Modifier.size(48.dp)
            .toggleable(selected, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics { contentDescription = description.orEmpty() },
            contentAlignment = if (atThumbnailCorner) Alignment.TopStart else Alignment.Center) {
            // Visual circle touches the thumbnail edges; the independent 48dp
            // target keeps selection easy without moving the indicator inward.
            indicator()
        }
    }
}
