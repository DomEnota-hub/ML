package com.domenota.medialoader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Only one copy of the item count appears in the deletion prompt. */
internal fun deleteMediaCountLabel(count: Int): String {
    require(count > 0)
    val remainder100 = count % 100
    val remainder10 = count % 10
    val noun = when {
        remainder100 in 11..14 -> "объектов"
        remainder10 == 1 -> "объект"
        remainder10 in 2..4 -> "объекта"
        else -> "объектов"
    }
    return "$count $noun"
}

internal fun deleteMediaPrompt(count: Int, permanently: Boolean): String {
    val amount = deleteMediaCountLabel(count)
    val pronoun = if (count == 1) "Его" else "Их"
    return if (permanently) {
        "Удалить $amount навсегда? $pronoun восстановить не получится."
    } else {
        "Переместить $amount в корзину? $pronoun можно будет восстановить."
    }
}

/**
 * Modal anchored above the navigation bar, matching the built-in gallery's
 * delete confirmation. No destructive action runs until the user taps Delete.
 */
@Composable
internal fun LibraryMediaDeleteSheet(
    count: Int,
    permanently: Boolean,
    onPermanentChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < .5f
    val surface = if (dark) Color(0xFF242424) else Color.White
    val onSurface = if (dark) Color(0xFFF5F5F5) else Color(0xFF171717)
    val secondary = if (dark) Color(0xFFD1D1D1) else Color(0xFF484848)
    val buttonSurface = if (dark) Color(0xFF404040) else Color(0xFFF0F0F0)
    val danger = if (dark) Color(0xFFFF636A) else Color(0xFFDA242C)

    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().navigationBarsPadding()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            contentAlignment = Alignment.BottomCenter) {
            Surface(
                modifier = Modifier.widthIn(max = 540.dp).fillMaxWidth(),
                shape = RoundedCornerShape(30.dp),
                color = surface,
                shadowElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 22.dp, end = 22.dp, top = 26.dp, bottom = 25.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Удалить", fontSize = 23.sp, fontWeight = FontWeight.SemiBold,
                        color = onSurface, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(19.dp))
                    Text(deleteMediaPrompt(count, permanently),
                        modifier = Modifier.fillMaxWidth(), fontSize = 16.sp,
                        lineHeight = 23.sp, color = secondary)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .toggleable(value = permanently, role = Role.Checkbox,
                                onValueChange = onPermanentChange),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(24.dp)
                                .border(1.6.dp, if (permanently) danger else secondary, CircleShape)
                                .background(if (permanently) danger else Color.Transparent, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (permanently) {
                                androidx.compose.material3.Icon(Icons.Rounded.Check, null,
                                    Modifier.size(17.dp), tint = Color.White)
                            }
                        }
                        Text("Удалить навсегда", fontSize = 15.sp,
                            color = if (permanently) danger else onSurface)
                    }
                    Spacer(Modifier.height(15.dp))
                    Row(Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = buttonSurface,
                                contentColor = onSurface),
                            elevation = null) {
                            Text("Отмена", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        }
                        Button(onClick = onDelete,
                            modifier = Modifier.weight(1f).height(56.dp),
                            shape = RoundedCornerShape(17.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = buttonSurface,
                                contentColor = danger),
                            elevation = null) {
                            Text("Удалить", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
