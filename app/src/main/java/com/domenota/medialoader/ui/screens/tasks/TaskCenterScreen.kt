package com.domenota.medialoader.ui.screens.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.domenota.medialoader.data.tasks.*
import com.domenota.medialoader.ui.components.ReportDrawerScreen

internal const val TASK_CENTER_ROUTE = "task-center"

@Composable
internal fun TaskCenterAction(onOpen: () -> Unit) {
    val tasks by TaskCenter.registry.tasks.collectAsStateWithLifecycle()
    val count = tasks.count { it.active }
    IconButton(onOpen) {
        BadgedBox(badge = { if (count > 0) Badge { Text(count.toString()) } }) {
            Icon(Icons.Rounded.List, "Центр задач")
        }
    }
}
@Composable
internal fun TaskCenterScreen(onBack: () -> Unit) {
    ReportDrawerScreen(true)
    val tasks by TaskCenter.registry.tasks.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if(settingsOpen) TaskSettingsDialog { settingsOpen=false }
    val active = tasks.filter { it.active }
    val waiting = tasks.filter { !it.active && !it.status.terminal }
    val history = tasks.filter { it.status.terminal }
    val visible = when (tab) { 0 -> active; 1 -> waiting; else -> history }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            Text("Центр задач", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
            TextButton({ settingsOpen=true }) { Text("Настройки") }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Активные: ${active.size}", style = MaterialTheme.typography.labelMedium)
            Text("Ожидают: ${waiting.size}", style = MaterialTheme.typography.labelMedium)
            Text("История: ${history.size}", style = MaterialTheme.typography.labelMedium)
        }
        TabRow(selectedTabIndex = tab) {
            listOf("Активные", "Ожидают", "История").forEachIndexed { index, label ->
                Tab(selected = tab == index, onClick = { tab = index }, text = { Text(label) })
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (visible.isEmpty()) item { Text("Нет задач", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(visible, key = { it.id }) { row -> TaskCard(row) }
        }
    }
}
@Composable
private fun TaskCard(row: TaskSnapshot) {
    var expanded by rememberSaveable(row.id) { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(row.kind.label, style = MaterialTheme.typography.titleMedium)
            Text(row.status.label + (row.wait?.let { " · ${it.label}" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            row.stage?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
            row.percent?.let { LinearProgressIndicator(progress = { it / 100f }, modifier = Modifier.fillMaxWidth()); Text("$it%") }
            val progress = row.files ?: row.progress
            progress?.let {
                val fraction = it.fraction
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                else if (row.active) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (row.files != null) Text("${it.done} из ${it.total ?: "?"}", style = MaterialTheme.typography.bodySmall)
                else if (it.done > 0 || it.total != null) Text("${it.done} / ${it.total ?: "?"} байт", style = MaterialTheme.typography.bodySmall)
            }
            if (row.active && progress == null && row.percent == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (row.errors > 0) Text("Ошибки: ${row.errors}", color = MaterialTheme.colorScheme.error)
            Text(if (expanded) "Свернуть" else "Подробнее", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        if (expanded) Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TaskCommands(row)
            row.children.forEach { child ->
                HorizontalDivider()
                Text(child.kind.label + " · " + child.status.label, style = MaterialTheme.typography.bodyMedium)
                child.wait?.let { Text(it.label, style = MaterialTheme.typography.bodySmall) }
                child.progress?.let { Text("${it.done} / ${it.total ?: "?"} байт", style = MaterialTheme.typography.bodySmall) }
                TaskCommands(child)
            }
        }
    }
}
@Composable
private fun TaskCommands(row: TaskSnapshot) {
    row.commands.forEach { action ->
        TextButton({ TaskCenter.registry.dispatch(row.id, action) }) { Text(action.label) }
    }
}

@Composable
private fun TaskSettingsDialog(onClose:()->Unit) {
    val value by TaskHistoryStore.settings.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf(false) }
    if(confirm) AlertDialog(onDismissRequest={confirm=false},title={Text("Очистить историю задач?")},
        text={Text("Файлы, результаты и история загрузок сохранятся.")},
        confirmButton={TextButton({TaskCenter.registry.clearHistory();confirm=false}){Text("Очистить")}},
        dismissButton={TextButton({confirm=false}){Text("Отмена")}})
    else AlertDialog(onDismissRequest=onClose,title={Text("Настройки Центра задач")},
        text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("Хранение истории")
                TaskSettings.RETENTION.forEach { days ->
                    Row(Modifier.fillMaxWidth().clickable { TaskHistoryStore.update(value.copy(retention=days)) },verticalAlignment=Alignment.CenterVertically) {
                        RadioButton(selected=value.retention==days,onClick={TaskHistoryStore.update(value.copy(retention=days))})
                        Text(if(days==0) "Без ограничений" else "$days дн.")
                    }
                }
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Тяжёлая индексация при зарядке",Modifier.weight(1f)); Switch(value.charging,{TaskHistoryStore.update(value.copy(charging=it))}) }
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Учитывать энергосбережение",Modifier.weight(1f)); Switch(value.batterySaver,{TaskHistoryStore.update(value.copy(batterySaver=it))}) }
                Row(verticalAlignment=Alignment.CenterVertically) { Text("Уведомления о завершении",Modifier.weight(1f)); Switch(value.notifications,{TaskHistoryStore.update(value.copy(notifications=it))}) }
                TextButton({confirm=true}){Text("Очистить историю задач")}
            }
        },confirmButton={TextButton(onClose){Text("Готово")}})
}
