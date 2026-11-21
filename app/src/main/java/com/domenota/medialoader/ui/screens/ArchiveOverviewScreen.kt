package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.data.catalog.visibility.SelectiveArchiveQaEntry

internal data class ArchiveQaFolder(val id:String,val name:String,val count:Int)

/** QA access is NOT a toggle that bypasses MediaStore validation: it only
 * opens a read-only preflight and a second explicit confirmation dialog.
 * The per-file mover may still refuse at any identity/references/privacy gate.
 * Restore is always accessible even if the test-mode checkbox is off. */
@Composable
internal fun ArchiveOverviewScreen(
    knownHiddenFolders: Int,
    onClose: () -> Unit,
    onOpenFolders: () -> Unit,
    candidates:List<ArchiveQaFolder> = emptyList(),
    recovery:List<SelectiveArchiveQaEntry> = emptyList(),
    busy:Boolean = false,
    error:String? = null,
    onRefresh:(()->Unit)? = null,
    onPreview:((String)->Unit)? = null,
    onRestore:((String)->Unit)? = null,
) {
    var trialEnabled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { onRefresh?.invoke() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .testTag("archive-overview").padding(16.dp),
        verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment=Alignment.CenterVertically) {
            IconButton(onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack,"Вернуться из Архива") }
            Icon(Icons.Rounded.Archive,null,Modifier.size(27.dp),
                tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text("Архив",style=MaterialTheme.typography.headlineSmall,
                fontWeight=FontWeight.SemiBold)
        }
        Text("Изменения видимости выполняются только после проверки точного содержимого. " +
            "Архив не удаляет файлы и не заменяет «Личное» или «Сейф».",
            style=MaterialTheme.typography.bodyMedium,
            color=MaterialTheme.colorScheme.onSurfaceVariant)
        if(busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("archive-qa-progress"))
            Text("Проверяю журнал или выбранные файлы. Не закрывайте приложение во время переноса.",
                style=MaterialTheme.typography.bodySmall)
        }
        if(error!=null) Text(error,style=MaterialTheme.typography.bodySmall,
            color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("archive-qa-error"))
        Surface(shape=RoundedCornerShape(18.dp),
            color=MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Folder,null,tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Папка целиком",style=MaterialTheme.typography.titleMedium)
                }
                Text("Допустимо скрытие через .nomedia только в отдельном физическом каталоге, " +
                    "если проверены все файлы и действующие разрешения Android.",
                    style=MaterialTheme.typography.bodySmall)
                Text("В реестре ML скрытых папок: ${knownHiddenFolders.coerceAtLeast(0)}. " +
                    "Индексация системной галереи требует отдельной проверки.",
                    style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick=onOpenFolders,Modifier.fillMaxWidth().testTag("archive-open-folders")) {
                    Icon(Icons.Rounded.Folder,null)
                    Spacer(Modifier.width(8.dp))
                    Text("Открыть папки")
                }
            }
        }
        Surface(shape=RoundedCornerShape(18.dp),
            color=MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield,null,tint=MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Выбранные файлы",style=MaterialTheme.typography.titleMedium)
                }
                Text("Тестовый режим: перемещаются только файлы выбранной папки ML, " +
                    "без .nomedia на общей физической папке. Исходные ссылки, байты и даты " +
                    "проверяются до и после переноса. При сбое возможна ручная диагностика.",
                    style=MaterialTheme.typography.bodySmall)
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Checkbox(checked=trialEnabled,onCheckedChange={ trialEnabled=it },
                        enabled=!busy && onPreview!=null,
                        modifier=Modifier.testTag("archive-qa-consent"))
                    Spacer(Modifier.width(6.dp))
                    Text("Проверяю только тестовые копии. Понимаю риск прерывания переноса.",
                        style=MaterialTheme.typography.bodySmall)
                }
                Text("Для первой аппаратной проверки: от 1 до 12 файлов в одной " +
                    "виртуальной папке ML, находящихся в общем физическом каталоге.",
                    style=MaterialTheme.typography.labelSmall,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                candidates.forEach { folder ->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,
                        horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(folder.name,style=MaterialTheme.typography.bodyMedium)
                            Text("${folder.count} файлов",style=MaterialTheme.typography.labelSmall)
                        }
                        OutlinedButton(onClick={ onPreview?.invoke(folder.id) },
                            enabled=trialEnabled && !busy && onPreview!=null &&
                                folder.count in 1..12 &&
                                recovery.none { it.folderId==folder.id && !it.restored },
                            modifier=Modifier.testTag("archive-qa-preview-${folder.id}")) {
                            Text("Проверить")
                        }
                    }
                }
                if(candidates.isEmpty())
                    Text("Создайте папку ML и добавьте туда тестовые копии файлов.",
                        style=MaterialTheme.typography.bodySmall)
            }
        }
        if(recovery.isNotEmpty()) {
            Surface(shape=RoundedCornerShape(18.dp),
                color=MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text("Журнал выборочного архива",
                        style=MaterialTheme.typography.titleMedium)
                    Text("Восстановление доступно независимо от тестового переключателя. " +
                        "Состояние определяется сохранённым журналом, а не текущим списком Галереи.",
                        style=MaterialTheme.typography.bodySmall)
                    recovery.forEach { item ->
                        Row(Modifier.fillMaxWidth(),
                            verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(candidates.firstOrNull { it.id==item.folderId }?.name
                                    ?: "Папка из журнала")
                                val status=when {
                                    item.needsReview -> "Требуется проверка журнала"
                                    item.restored -> "Восстановлено и проверено"
                                    item.hidden==item.total -> "Скрыто и проверено: ${item.hidden}"
                                    else -> "Незавершённая операция: ${item.hidden} из ${item.total}"
                                }
                                Text(status,style=MaterialTheme.typography.labelSmall)
                            }
                            if(!item.restored) OutlinedButton(
                                onClick={ onRestore?.invoke(item.folderId) },
                                enabled=!busy && !item.needsReview && onRestore!=null,
                                modifier=Modifier.testTag("archive-qa-restore-${item.folderId}")) {
                                Icon(Icons.Rounded.Restore,null,Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Вернуть")
                            }
                        }
                    }
                }
            }
        }
        TextButton(onClick={onRefresh?.invoke()},enabled=!busy && onRefresh!=null,
            modifier=Modifier.testTag("archive-qa-refresh")) { Text("Обновить журнал") }
    }
}
