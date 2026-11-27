package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.components.drawerEntryIcon
import com.domenota.medialoader.ui.navigation.DrawerConfiguration
import com.domenota.medialoader.ui.navigation.DrawerEntry
import com.domenota.medialoader.ui.navigation.drawerAvailableEntries
import com.domenota.medialoader.ui.navigation.mandatoryDrawerEntries
import kotlinx.coroutines.delay
import kotlin.math.abs

/** One editable order including hidden entries. The handle alone captures a
 * long press; normal vertical scrolling and switch taps stay independent. */
@Composable
internal fun DrawerSettingsScreen(
    configuration: DrawerConfiguration,
    developerAllowed: Boolean,
    onChange: (DrawerConfiguration) -> Unit,
    onBack: () -> Unit,
    advancedToolsVisible:Boolean=false,
) {
    val latestChange by rememberUpdatedState(onChange)
    val scroll = rememberScrollState()
    val colors = MaterialTheme.colorScheme
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    var draft by remember { mutableStateOf(configuration) }
    var dragged by remember { mutableStateOf<String?>(null) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var viewportTop by remember { mutableFloatStateOf(0f) }
    var viewportBottom by remember { mutableFloatStateOf(0f) }
    val centers = remember { mutableStateMapOf<String, Float>() }
    val admitted = remember(developerAllowed,advancedToolsVisible) { drawerAvailableEntries(developerAllowed,advancedToolsVisible) }
    val entries = draft.editableItems(admitted)

    LaunchedEffect(configuration) {
        if (dragged == null) draft = configuration
    }
    LaunchedEffect(developerAllowed,advancedToolsVisible) {
        if (dragged?.let { it !in draft.editableItems(admitted) } == true) {
            dragged = null
            draft = configuration
        }
    }
    fun save(next: DrawerConfiguration) {
        draft = next
        latestChange(next)
    }
    fun moveToFinger() {
        val moving = dragged ?: return
        val target = draft.editableItems(admitted).asSequence().filter { it in centers }
            .minByOrNull { abs((centers[it] ?: dragCenter) - dragCenter) } ?: return
        if (target != moving) draft = draft.moveItem(moving, target)
    }
    fun finishDrag() {
        if (dragged != null) {
            dragged = null
            latestChange(draft)
        }
    }
    LaunchedEffect(dragged) {
        if (dragged == null) return@LaunchedEffect
        while (dragged != null) {
            val speed = when {
                dragCenter < viewportTop + edge -> -((viewportTop + edge - dragCenter) / edge).coerceIn(0f, 1f)
                dragCenter > viewportBottom - edge -> ((dragCenter - viewportBottom + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (speed != 0f) {
                scroll.scrollBy(speed * edge / 4)
                // Wait for the post-scroll layout to provide updated row positions.
                withFrameNanos { }
                moveToFinger()
            }
            delay(16)
        }
    }

    Column(Modifier.fillMaxSize().testTag("drawer-settings-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finishDrag(); onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад")
            }
            Text("Боковое меню", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Удерживай полоски справа для перемещения",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned {
            viewportTop = it.positionInRoot().y
            viewportBottom = viewportTop + it.size.height
        }) {
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entries.forEach { id ->
                    key(id) {
                        val entry=DrawerConfiguration.entry(id)
                        val label=entry?.label ?: "Разделитель"
                        val enabled = entry != null && entry in draft.visible
                        val fixed = entry != null && entry in mandatoryDrawerEntries
                        val activeDrag = dragged == id
                        val iconTint = if (enabled) colors.primary else colors.onSurfaceVariant
                        Surface(
                            modifier = Modifier.fillMaxWidth().testTag("drawer-config-" + id)
                                .onGloballyPositioned { coordinates ->
                                    centers[id] = coordinates.positionInRoot().y + coordinates.size.height / 2f
                                },
                            color = if (activeDrag) colors.primaryContainer else colors.surfaceContainer,
                            shape = RoundedCornerShape(14.dp),
                            border = if (activeDrag) androidx.compose.foundation.BorderStroke(1.dp, colors.primary) else null,
                        ) {
                            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                if(entry==null) {
                                    Column(Modifier.weight(1f)) {
                                        HorizontalDivider(color=colors.outline)
                                        Text(label,style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                                    }
                                    IconButton(onClick={save(draft.removeDivider(id))},enabled=dragged==null) {
                                        Icon(Icons.Rounded.DeleteOutline,"Удалить разделитель",tint=colors.error)
                                    }
                                } else {
                                    Icon(drawerEntryIcon(entry), null, Modifier.size(24.dp), tint = iconTint)
                                    Text(label, Modifier.weight(1f), color = if (enabled) colors.onSurface else colors.onSurfaceVariant)
                                    Switch(checked = enabled, enabled = !fixed && dragged == null,
                                        onCheckedChange = { save(draft.setVisible(entry, it)) },
                                        modifier = Modifier.testTag("drawer-config-switch-" + id)
                                            .semantics { contentDescription = if (fixed) label + ": всегда доступно" else label })
                                }
                                Box(Modifier.size(48.dp).testTag("drawer-config-handle-" + id)
                                    .semantics {
                                        contentDescription = "Переместить " + label
                                        customActions = buildList {
                                            val index = entries.indexOf(id)
                                            entries.getOrNull(index - 1)?.let { target ->
                                                add(CustomAccessibilityAction("Выше") {
                                                    save(draft.moveItem(id, target)); true
                                                })
                                            }
                                            entries.getOrNull(index + 1)?.let { target ->
                                                add(CustomAccessibilityAction("Ниже") {
                                                    save(draft.moveItem(id, target)); true
                                                })
                                            }
                                        }
                                    }
                                    .pointerInput(id, developerAllowed,advancedToolsVisible) {
                                        detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                centers[id]?.let { center ->
                                                    dragCenter = center
                                                    dragged = id
                                                }
                                            },
                                            onDragEnd = { finishDrag() },
                                            onDragCancel = { finishDrag() },
                                            onDrag = { change, delta ->
                                                if (dragged == id) {
                                                    change.consume()
                                                    dragCenter = (dragCenter + delta.y).coerceIn(viewportTop, viewportBottom)
                                                    moveToFinger()
                                                }
                                            },
                                        )
                                    }, contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.Menu, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }
        OutlinedButton(onClick={save(draft.addDivider())},enabled=dragged==null && draft.layout.count { DrawerConfiguration.isDivider(it) }<20,
            modifier=Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=2.dp).testTag("drawer-config-add-divider")) {
            Icon(Icons.Rounded.Add,null);Text("Добавить разделитель")
        }
        OutlinedButton(
            onClick = { save(DrawerConfiguration.DEFAULT) }, enabled = dragged == null,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .testTag("drawer-config-reset"),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.primary),
        ) { Text("Восстановить по умолчанию") }
    }
}
