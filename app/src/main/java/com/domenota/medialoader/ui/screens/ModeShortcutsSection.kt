package com.domenota.medialoader.ui.screens

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.Collections
import androidx.compose.material.icons.rounded.AudioFile
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
        grouped = true, expanded = expanded, onClick = { expanded = !expanded; refresh() })
    ExpandableSettingsBody(expanded) {
        listOf(AppMode.GALLERY, AppMode.MUSIC).forEach { mode ->
            GroupDivider()
            val added = ModeShortcuts.id(mode) in pinned
            val label = if (mode == AppMode.GALLERY) "Галерея" else "Музыка"
            SettingRow(if (mode == AppMode.GALLERY) Icons.Rounded.Collections else Icons.Rounded.AudioFile,
                label, if (!supported) "Лаунчер не поддерживает создание" else if (added) "Добавлен" else "Создать ярлык",
                grouped = true, nested = true, onClick = {
                    if (added) refresh() else {
                        val accepted = runCatching { ModeShortcuts.request(context,mode) }.getOrDefault(false)
                        Toast.makeText(context, if (accepted) "Запрос создания ярлыка отправлен" else "Создание ярлыка недоступно", Toast.LENGTH_SHORT).show()
                        refresh()
                    }
                })
        }
    }
}
