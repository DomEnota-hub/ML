package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Redo
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.domenota.medialoader.R

/** People use EfficientDet + MobileSAM + LaMa. Glare/reflection require separate models. */
internal enum class AiEraserMode(val title: String, val icon: Int, val available: Boolean) {
    OBJECTS("Удаление объектов", R.drawable.ic_ai_eraser_objects, true),
    PEOPLE("Удаление людей", R.drawable.ic_ai_eraser_people, true),
    GLARE("Убрать блики", R.drawable.ic_ai_eraser_glare, false),
    REFLECTION("Удалить отражение", R.drawable.ic_ai_eraser_reflection, false),
    MANUAL("Вручную", R.drawable.ic_ai_eraser_manual, true),
    TEXT("Удалить текст", R.drawable.ic_ai_eraser_text, true),
}

/**
 * Specialist modes stay unavailable until their own on-device pipeline exists.
 * MobileSAM is a point-prompted object segmenter, not a people detector.
 * The explanatory card is tappable; it NEVER activates an unfinished mode.
 */
internal val AiEraserMode.unavailableExplanation: String?
    get() = when (this) {
        AiEraserMode.PEOPLE -> null
        AiEraserMode.GLARE ->
            "Удаление бликов пока недоступно: отдельная модель коррекции света ещё не подключена."
        AiEraserMode.REFLECTION ->
            "Удаление отражений пока недоступно: отдельная модель обработки отражений ещё не подключена."
        else -> null
    }

private val eraserYellow = Color(0xFFFFC21C)
private val eraserSurface = Color(0xFF282828)

/** The 0.7 prototype's compact 74 × 68dp cards, 23dp glyphs and left-aligned labels. */
@Composable
internal fun AiEraserModeStrip(
    selected: AiEraserMode,
    enabled: Boolean,
    onMode: (AiEraserMode) -> Unit,
    onUnavailable: (AiEraserMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(91.dp).horizontalScroll(rememberScrollState())
            .padding(start = 7.dp, end = 7.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AiEraserMode.entries.forEach { mode ->
            val active = selected == mode
            val foreground = if (active) Color(0xFF161616) else Color.White
            Column(
                Modifier.width(74.dp).height(68.dp)
                    .background(if (active) eraserYellow else eraserSurface,
                        RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) {
                        if (mode.available) onMode(mode) else onUnavailable(mode)
                    }
                    .padding(horizontal = 6.dp, vertical = 6.dp)
                    .semantics {
                        contentDescription = mode.title
                        if (!mode.available) stateDescription = "Пока недоступно"
                    },
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Icon(
                    painter = painterResource(mode.icon),
                    contentDescription = null,
                    tint = foreground.copy(alpha = if (mode.available) 1f else .45f),
                    modifier = Modifier.align(Alignment.End).size(23.dp),
                )
                Text(
                    mode.title,
                    color = foreground.copy(alpha = if (mode.available) 1f else .45f),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 10.7.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Start,
                )
            }
        }
    }
}

/** Only OBJECTS owns the automatic/manual switch. A disabled model may be installed by tapping A. */
@Composable
internal fun AiEraserObjectSwitch(
    enabled: Boolean,
    automatic: Boolean,
    autoAvailable: Boolean,
    onAuto: () -> Unit,
    onManual: () -> Unit,
) {
    Row(
        Modifier.background(Color(0xFF303033), RoundedCornerShape(20.dp))
            .height(35.dp).padding(3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier.width(46.dp).height(29.dp)
                .background(if (automatic) eraserYellow else Color.Transparent,
                    RoundedCornerShape(18.dp))
                .clickable(enabled = enabled, onClick = onAuto)
                .semantics { contentDescription =
                    if (autoAvailable) "Автоматическое выделение объектов"
                    else "Установить MobileSAM для выделения объектов"
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_ai_eraser_auto), null,
                modifier = Modifier.size(18.dp),
                tint = if (automatic) Color(0xFF191919) else Color(0xFFB7B7B7))
        }
        Box(
            Modifier.width(46.dp).height(29.dp)
                .background(if (!automatic) eraserYellow else Color.Transparent,
                    RoundedCornerShape(18.dp))
                .clickable(enabled = enabled, onClick = onManual)
                .semantics { contentDescription = "Ручное выделение объектов" },
            contentAlignment = Alignment.Center,
        ) {
            // Same SVG hand as the MANUAL mode, without its rectangular outline.
            Icon(painterResource(R.drawable.ic_ai_eraser_hand), null,
                modifier = Modifier.size(18.dp),
                tint = if (!automatic) Color(0xFF191919) else Color(0xFFB7B7B7))
        }
    }
}

/** The compare control is *hold to preview*, not an on/off switch. */
@Composable
internal fun AiEraserTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canCompare: Boolean,
    busy: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onCompareHold: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onUndo, enabled = canUndo && !busy,
            modifier = Modifier.size(39.dp),
        ) {
            Icon(Icons.Rounded.Undo, contentDescription = "Отменить",
                modifier = Modifier.size(22.dp),
                tint = if (canUndo && !busy) Color.White else Color.DarkGray)
        }
        Spacer(Modifier.width(4.dp))
        IconButton(
            onClick = onRedo, enabled = canRedo && !busy,
            modifier = Modifier.size(39.dp),
        ) {
            Icon(Icons.Rounded.Redo, contentDescription = "Повторить",
                modifier = Modifier.size(22.dp),
                tint = if (canRedo && !busy) Color.White else Color.DarkGray)
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.size(42.dp)
                .pointerInput(canCompare, busy) {
                    if (canCompare && !busy) detectTapGestures(onPress = {
                        onCompareHold(true)
                        try { tryAwaitRelease() } finally { onCompareHold(false) }
                    })
                }
                .semantics {
                    contentDescription = "Удерживать, чтобы показать оригинал"
                    if (!canCompare || busy) disabled()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_ai_eraser_compare), null,
                modifier = Modifier.size(26.dp),
                tint = if (canCompare && !busy) Color.White else Color.DarkGray)
        }
    }
}

@Composable
internal fun AiEraserFooter(
    canApply: Boolean,
    onCancel: () -> Unit,
    onApply: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Отмена",
                modifier = Modifier.size(27.dp), tint = Color.White)
        }
        Text("AI-ластик Pro", fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
            color = Color.White)
        IconButton(onClick = onApply, enabled = canApply,
            modifier = Modifier.size(36.dp)) {
            Icon(Icons.Rounded.Check, contentDescription = "Готово",
                modifier = Modifier.size(27.dp),
                tint = if (canApply) Color.White else Color.DarkGray)
        }
    }
}
