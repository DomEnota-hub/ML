package com.domenota.medialoader.ui.screens.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Approved MediaLoader photo/video viewer v10. Geometries follow the HTML reference exactly. */
private val v10MenuSurface = Color(0xFF2C2C2D)
private val v10Muted = Color(0xFF8F8F90)

@Composable
internal fun GalleryV10Header(date: String, time: String, onBack: () -> Unit, onRotate: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(77.dp).background(Color(0x99000000)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Назад", modifier = Modifier.size(29.dp), tint = Color.White)
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(date, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(3.dp))
            Text(time, color = v10Muted, fontSize = 12.sp, maxLines = 1)
        }
        IconButton(onClick = onRotate, modifier = Modifier.size(38.dp).semantics { contentDescription = "Повернуть" }) {
            GalleryV10Icon("rotate", Modifier.size(27.dp))
        }
    }
}

@Composable
internal fun GalleryV10Bottom(
    isPhoto: Boolean, isFavorite: Boolean, onShare: () -> Unit, onEdit: () -> Unit,
    onFavorite: () -> Unit, onDelete: () -> Unit, onMenu: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(80.dp).background(Color(0x88000000)).padding(horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            Triple("share", "Поделиться", onShare),
            Triple(if (isPhoto) "photoedit" else "cut", "Редактировать", onEdit),
            Triple("heart", "Избранное", onFavorite),
            Triple("trash", "Удалить", onDelete),
            Triple("more", "Действия с файлом", onMenu),
        ).forEach { (key, label, click) ->
            IconButton(onClick = click, modifier = Modifier.size(width = 59.dp, height = 58.dp).semantics { contentDescription = label }) {
                GalleryV10Icon(key, Modifier.size(30.dp))
            }
        }
    }
}

private data class V10MenuRow(val id: String, val title: String, val icon: String, val hasSub: Boolean = false)
private val v10PhotoRows = listOf(
    V10MenuRow("album", "В альбом", "album"),
    V10MenuRow("wallpaper", "Установить как обои", "wallpaper"),
    V10MenuRow("info", "Сведения", "info"),
    V10MenuRow("divider1", "", ""),
    V10MenuRow("extract", "Извлечь", "extract", true),
    V10MenuRow("cutout", "Вырезка фото", "cutout"),
    V10MenuRow("edit", "Изменить", "photoedit_menu"),
    V10MenuRow("watermark", "Защитный водяной знак", "watermark"),
    V10MenuRow("cast", "Транслировать", "cast"),
    V10MenuRow("divider2", "", ""),
    V10MenuRow("copy", "Копировать", "copy"),
    V10MenuRow("open", "Открыть другим приложением", "open"),
    V10MenuRow("divider3", "", ""),
    V10MenuRow("private", "Переместить в личное", "lock"),
)
private val v10VideoRows = listOf(
    V10MenuRow("album", "В альбом", "album"),
    V10MenuRow("wallpaper", "Установить как обои", "wallpaper"),
    V10MenuRow("info", "Сведения", "info"),
    V10MenuRow("divider1", "", ""),
    V10MenuRow("cast", "Транслировать", "cast"),
    V10MenuRow("loop", "Зациклить одно видео", "loop"),
    V10MenuRow("speed", "Скорость воспроизведения", "speed", true),
    V10MenuRow("song", "Найти трек", "music"),
    V10MenuRow("divider2", "", ""),
    V10MenuRow("copy", "Копировать", "copy"),
    V10MenuRow("open", "Открыть другим приложением", "open"),
    V10MenuRow("divider3", "", ""),
    V10MenuRow("private", "Переместить в личное", "lock"),
)

/** Stable labels are verified against the approved photo/video action lists. */
internal fun galleryV10ActionLabels(isPhoto: Boolean): List<String> =
    (if (isPhoto) v10PhotoRows else v10VideoRows)
        .filterNot { it.id.startsWith("divider") }
        .map { it.title }

/** Keep nested action surfaces adjacent to their selected parent row, respecting short screens. */
internal fun galleryV10SubmenuTop(anchor: androidx.compose.ui.unit.Dp, viewport: androidx.compose.ui.unit.Dp, panel: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp {
    val freeEnd = (viewport - panel - 8.dp).coerceAtLeast(8.dp)
    return (anchor - 8.dp).coerceIn(8.dp, freeEnd)
}

/** Actual actions are supplied by the hosting screen; this popup never silently simulates them. */
@Composable
internal fun GalleryV10Popup(
    isPhoto: Boolean,
    repeatOne: Boolean,
    speed: Float,
    allowPrivate: Boolean,
    onDismiss: () -> Unit,
    onAction: (String) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    var secondary by remember(isPhoto) { mutableStateOf<String?>(null) }
    val anchorPositions = remember(isPhoto) { mutableStateMapOf<String, Float>() }
    var rootTopPx by remember { mutableStateOf(0f) }
    val density = LocalDensity.current
    BackHandler(secondary != null) { secondary = null }
    BackHandler(secondary == null) { onDismiss() }
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { rootTopPx = it.positionInRoot().y }) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .55f)).clickable(onClick = onDismiss))
        val menuWidth = minOf(287.dp, maxWidth - 32.dp)
        val bodyHeight = maxHeight * .65f
        val menuRows = (if (isPhoto) v10PhotoRows else v10VideoRows).filter { allowPrivate || it.id != "private" }
        Column(
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = maxHeight * .23f)
                .width(menuWidth).heightIn(max = bodyHeight).clip(RoundedCornerShape(17.dp))
                .background(v10MenuSurface).verticalScroll(rememberScrollState())
                .padding(top = 13.dp, bottom = 12.dp),
        ) {
            menuRows.forEach { row ->
                if (row.id.startsWith("divider")) {
                    Spacer(Modifier.height(10.dp))
                    Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp).height(1.dp).background(Color(0x66666666)))
                    Spacer(Modifier.height(10.dp))
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth().heightIn(min = 55.dp)
                            .onGloballyPositioned { coordinates ->
                                if (row.hasSub) anchorPositions[row.id] = coordinates.positionInRoot().y - rootTopPx
                            }
                            .clickable {
                                when (row.id) {
                                    "extract", "speed" -> secondary = row.id
                                    else -> onAction(row.id)
                                }
                            }
                            .padding(horizontal = 18.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Box(Modifier.size(29.dp), contentAlignment = Alignment.Center) {
                            GalleryV10Icon(row.icon, Modifier.size(if (row.id == "open") 23.dp else 29.dp))
                        }
                        Text(row.title, modifier = Modifier.weight(1f), color = Color.White,
                            fontSize = if (row.id == "open") 13.3.sp else 14.sp,
                            fontWeight = FontWeight(if (row.id == "open") 570 else if (row.id == "private") 550 else 600),
                            letterSpacing = if (row.id == "open") (-.25).sp else 0.sp,
                            maxLines = if (row.id == "open") 1 else 2,
                            overflow = TextOverflow.Clip,
                            lineHeight = 17.5.sp)
                        if (row.id == "loop" && repeatOne) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Color(0xFF5993FF))
                        } else if (row.hasSub) {
                            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), tint = Color(0xFF888888))
                        }
                    }
                }
            }
        }
        val activeSubmenu = secondary
        if (activeSubmenu != null) {
            val maxPanelHeight = maxHeight * .54f
            val expectedPanelHeight = if (secondary == "extract") 124.dp else 398.dp
            val panelHeight = minOf(maxPanelHeight, expectedPanelHeight)
            val anchorDp = with(density) {
                (anchorPositions[activeSubmenu] ?: (maxHeight * .23f).toPx()).toDp()
            }
            val popupY = galleryV10SubmenuTop(anchorDp, maxHeight, panelHeight)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .53f)).clickable { secondary = null })
            Column(
                modifier = Modifier.align(Alignment.TopEnd).offset(x = (-16).dp, y = popupY)
                    .width(menuWidth).heightIn(max = maxPanelHeight)
                    .clip(RoundedCornerShape(17.dp)).background(v10MenuSurface)
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).padding(horizontal = 18.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(15.dp)) {
                    GalleryV10Icon(if (secondary == "extract") "extract" else "speed", Modifier.size(22.dp))
                    Text(if (secondary == "extract") "Извлечь текст" else "Скорость воспроизведения",
                        fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White,
                        modifier = Modifier.weight(1f))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x55555555)))
                if (secondary == "extract") {
                    V10SecondaryRow("Распознать текст", selected = false) { onAction("ocr") }
                } else {
                    listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f).forEach { value ->
                        V10SecondaryRow("${value}x", selected = value == speed) { onSpeed(value) }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun V10SecondaryRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 55.dp).clickable(onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            color = if (selected) Color(0xFF5C92FF) else Color.White)
        if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp), tint = Color(0xFF5993FF))
    }
}
