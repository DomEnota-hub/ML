package com.domenota.medialoader.ui.screens

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.MusicNote
import com.domenota.medialoader.ui.theme.SettingIconTone
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Switch
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.domenota.medialoader.data.library.AppMode
import com.domenota.medialoader.data.library.ModeShortcuts

@Composable
internal fun ModeShortcutsSection() {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var expanded by rememberSaveable { mutableStateOf(false) }
    var pinned by remember { mutableStateOf(emptySet<String>()) }
    var supported by remember { mutableStateOf(false) }
    var branded by remember { mutableStateOf(ModeShortcuts.branded(context)) }
    fun changeStyle(enabled: Boolean) {
        val updated = runCatching { ModeShortcuts.setBranded(context,enabled) }.getOrDefault(false)
        branded = ModeShortcuts.branded(context)
        if (!updated) Toast.makeText(context, "Стиль сохранён. Лаунчер не подтвердил обновление ярлыков.", Toast.LENGTH_LONG).show()
    }
    fun refresh() {
        supported = runCatching { ModeShortcuts.supported(context) }.getOrDefault(false)
        pinned = runCatching { ModeShortcuts.pinned(context) }.getOrDefault(emptySet())
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh() }
        lifecycle.addObserver(observer); refresh()
        onDispose { lifecycle.removeObserver(observer) }
    }
    SettingRow(Icons.Rounded.AddToHomeScreen, "Ярлыки рабочего стола", "Быстрый запуск режимов",
        grouped = true, nested = true, iconTone = SettingIconTone.SHORTCUTS, expanded = expanded, onClick = { expanded = !expanded; refresh() })
    ExpandableSettingsBody(expanded) {
        GroupDivider()
        SettingRow(Icons.Rounded.Style, "Стиль ярлыков", if (branded) "Фирменные иконки ML" else "Базовые иконки",
            grouped = true, nested = true, iconTone = SettingIconTone.SHORTCUT_STYLE, showChevron = false,
            trailing = { Switch(checked = branded, onCheckedChange = ::changeStyle) },
            onClick = { changeStyle(!branded) })
        listOf(AppMode.GALLERY, AppMode.MUSIC).forEach { mode ->
            GroupDivider()
            val added = ModeShortcuts.id(mode) in pinned
            val label = if (mode == AppMode.GALLERY) "Галерея" else "Музыка"
            SettingRow(if (mode == AppMode.GALLERY) Icons.Rounded.Collections else Icons.Rounded.MusicNote,
                label, if (!supported) "Лаунчер не поддерживает создание" else if (added) "Добавлен" else "Создать ярлык",
                grouped = true, nested = true, iconTone = if (mode == AppMode.GALLERY) SettingIconTone.SHORTCUT_GALLERY else SettingIconTone.SHORTCUT_MUSIC, onClick = {
                    if (added) refresh() else {
                        val accepted = runCatching { ModeShortcuts.request(context,mode) }.getOrDefault(false)
                        Toast.makeText(context, if (accepted) "Запрос создания ярлыка отправлен" else "Создание ярлыка недоступно", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                })
        }
    }
}
