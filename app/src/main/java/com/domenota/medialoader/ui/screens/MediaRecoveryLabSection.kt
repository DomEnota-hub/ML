package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.InsertPhoto
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.domenota.medialoader.ui.theme.SettingIconTone

/**
 * TEMPORARY, ISOLATED MediaStore migration lab.
 * Delete this file and the one MediaRecoveryLabSection() call from SettingsScreen
 * once original gallery chronology has a verified production solution.
 */
@Composable
internal fun MediaRecoveryLabSection(
    activeDownloadCount: Int,
    storageMigrationRunning: Boolean,
    storageMigrationMessage: String,
    onMigrateLegacyMedia: () -> Unit,
    galleryDateAudit: String,
    galleryDateAuditBusy: Boolean,
    onGalleryDateAudit: () -> Unit,
    republishTrialStatus: String,
    republishTrialBusy: Boolean,
    onRunGalleryRepublishTrial: () -> Unit,
    onRemoveGalleryRepublishTestCopies: () -> Unit,
    exifTrialStatus: String,
    exifTrialBusy: Boolean,
    onRunExifTrial: () -> Unit,
    onRemoveExifTrial: () -> Unit,
    dateRecoveryStatus: String,
    dateRecoveryBusy: Boolean,
    dateRecoveryReady: Boolean,
    onDateRecoveryPreview: () -> Unit,
    onDateRecoveryTrial: () -> Unit,
    dateRecoveryTrialDone: Boolean,
    onRestoreRemainingDates: () -> Unit,
    onUndoMediaDateChanges: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var migrateLegacyDialog by remember { mutableStateOf(false) }
    var dateTrialDialog by remember { mutableStateOf(false) }
    var republishTrialDialog by remember { mutableStateOf(false) }
    var removeRepublishTrialDialog by remember { mutableStateOf(false) }
    var dateAllDialog by remember { mutableStateOf(false) }
    var undoDateDialog by remember { mutableStateOf(false) }
    var exifTrialDialog by remember { mutableStateOf(false) }
    var removeExifTrialDialog by remember { mutableStateOf(false) }

    Spacer(Modifier.height(26.dp))
    Text("Лаборатория медиатеки · временно",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
    SettingsGroup {
        SettingRow(
            Icons.Rounded.BugReport, "Восстановление медиатеки",
            "Диагностика, EXIF и безопасные пробы · можно удалить после исправления",
            grouped = true, expanded = expanded, iconTone = SettingIconTone.DIAGNOSTICS,
            onClick = { expanded = !expanded },
        )
        ExpandableSettingsBody(expanded) {
            GroupDivider()
            SettingRow(Icons.Rounded.SystemUpdate, "Перенести старые файлы",
                when {
                    storageMigrationRunning -> storageMigrationMessage
                    activeDownloadCount > 0 -> "Дождитесь завершения загрузок"
                    storageMigrationMessage.isNotBlank() -> storageMigrationMessage
                    else -> "Download → Pictures / Music, без пересоздания дат"
                }, grouped = true, nested = true, iconTone = SettingIconTone.FILE_ACCESS,
                onClick = {
                    if (!storageMigrationRunning && activeDownloadCount == 0)
                        migrateLegacyDialog = true
                })
            GroupDivider()
            SettingRow(Icons.Rounded.Article, "Аудит дат MediaStore",
                if (galleryDateAuditBusy) "Проверяем записи Android…" else galleryDateAudit,
                grouped = true, nested = true, iconTone = SettingIconTone.LOG,
                onClick = {
                    if (!galleryDateAuditBusy && !storageMigrationRunning && activeDownloadCount == 0)
                        onGalleryDateAudit()
                })
            GroupDivider()
            Text("Новая проба · EXIF на одной JPEG-копии",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow(Icons.Rounded.PhotoCamera, "Проверить историческую дату в EXIF",
                if (exifTrialBusy) "Создаём тестовую EXIF-копию…" else exifTrialStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.RECOGNITION,
                onClick = {
                    if (!exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                        !storageMigrationRunning && activeDownloadCount == 0)
                        exifTrialDialog = true
                })
            SettingRow(Icons.Rounded.DeleteSweep, "Удалить пробу EXIF",
                "Проверка имени, владельца и SHA-256; исходник не трогаем",
                grouped = true, nested = true, iconTone = SettingIconTone.TRASH,
                onClick = {
                    if (!exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                        !storageMigrationRunning && activeDownloadCount == 0)
                        removeExifTrialDialog = true
                })
            GroupDivider()
            Text("Предыдущие эксперименты",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow(Icons.Rounded.SwapHoriz, "Проба обратного создания файлов",
                if (republishTrialBusy) "Создаём копии…" else republishTrialStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.AMBER,
                onClick = {
                    if (!republishTrialBusy && !dateRecoveryBusy &&
                        !storageMigrationRunning && activeDownloadCount == 0)
                        republishTrialDialog = true
                })
            SettingRow(Icons.Rounded.RestoreFromTrash, "Удалить пробные копии",
                "Только тестовый альбом обратной пересборки",
                grouped = true, nested = true, iconTone = SettingIconTone.CLEAR_HISTORY,
                onClick = {
                    if (!republishTrialBusy && !dateRecoveryBusy &&
                        !storageMigrationRunning && activeDownloadCount == 0)
                        removeRepublishTrialDialog = true
                })
            GroupDivider()
            SettingRow(Icons.Rounded.Tune, "Проверка времени файлов",
                if (dateRecoveryBusy) "Проверяем…" else dateRecoveryStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.BLUE,
                onClick = {
                    if (!dateRecoveryBusy && activeDownloadCount == 0)
                        onDateRecoveryPreview()
                })
            if (dateRecoveryReady && !dateRecoveryBusy) {
                SettingRow(Icons.Rounded.Bolt, "Проба изменения mtime",
                    "Только один файл, сохранён журнал",
                    grouped = true, nested = true, iconTone = SettingIconTone.QUALITY,
                    onClick = { if (activeDownloadCount == 0) dateTrialDialog = true })
            }
            if (dateRecoveryTrialDone && !dateRecoveryBusy) {
                SettingRow(Icons.Rounded.PlaylistAddCheck, "Восстановить даты остальных",
                    "При неэффективности метод автоматически блокируется",
                    grouped = true, nested = true, iconTone = SettingIconTone.AMBER,
                    onClick = { if (activeDownloadCount == 0) dateAllDialog = true })
            }
            SettingRow(Icons.Rounded.Info, "Откат изменения mtime",
                "Вернуть даты из журнала, без удаления файлов",
                grouped = true, nested = true, iconTone = SettingIconTone.MEDIA_MANAGEMENT,
                onClick = {
                    if (!dateRecoveryBusy && activeDownloadCount == 0)
                        undoDateDialog = true
                })
        }
    }

    if (undoDateDialog) AlertDialog(
        onDismissRequest = { undoDateDialog = false },
        title = { Text("Вернуть предыдущие даты файлов?") },
        text = { Text("Будут отменены только те изменения времени, которые " +
            "приложение записало в журнал восстановления. Фотографии, " +
            "видео и история загрузок не удаляются. Изменённые после " +
            "операции файлы пропускаются ради безопасности.") },
        confirmButton = {
            TextButton(onClick = {
                undoDateDialog = false
                if (!dateRecoveryBusy && activeDownloadCount == 0) onUndoMediaDateChanges()
            }) { Text("Отменить восстановление") }
        },
        dismissButton = {
            TextButton(onClick = { undoDateDialog = false }) { Text("Назад") }
        },
    )

    if (dateAllDialog) AlertDialog(
        onDismissRequest = { dateAllDialog = false },
        title = { Text("Восстановить даты остальных файлов?") },
        text = { Text("Продолжайте, только если после пробного изменения " +
            "один файл переместился на ожидаемую дату в системной галерее Xiaomi. " +
            "Изменяется время существующих файлов, но НЕ их содержимое, EXIF " +
            "или дата загрузки в истории. Каждая операция записывается в журнал. " +
            "Это НЕ обратное пересоздание файлов.") },
        confirmButton = {
            TextButton(onClick = {
                dateAllDialog = false
                if (!dateRecoveryBusy && dateRecoveryTrialDone && activeDownloadCount == 0)
                    onRestoreRemainingDates()
            }) { Text("Восстановить остальные даты") }
        },
        dismissButton = {
            TextButton(onClick = { dateAllDialog = false }) { Text("Отмена") }
        },
    )

    if (republishTrialDialog) AlertDialog(
        onDismissRequest = { republishTrialDialog = false },
        title = { Text("Проверить обратный порядок создания?") },
        text = { Text("Это проба на ТРЁХ отдельных копиях фотографий. " +
            "MediaLoader создаст их от самой старой загрузки к новой в отдельном " +
            "альбоме MediaLoader_QA_Order_Test. Оригиналы, альбомы и история " +
            "останутся нетронутыми. Копии получат сегодняшнюю дату добавления, " +
            "а НЕ настоящие исторические даты. После проверьте в Xiaomi Gallery " +
            "порядок 03_NEW, 02_MIDDLE, 01_OLD. Пробные копии можно удалить.") },
        confirmButton = {
            TextButton(onClick = {
                republishTrialDialog = false
                if (!republishTrialBusy && !storageMigrationRunning &&
                    !dateRecoveryBusy && activeDownloadCount == 0)
                    onRunGalleryRepublishTrial()
            }) { Text("Создать 3 проверенные копии") }
        },
        dismissButton = {
            TextButton(onClick = { republishTrialDialog = false }) { Text("Отмена") }
        },
    )

    if (removeRepublishTrialDialog) AlertDialog(
        onDismissRequest = { removeRepublishTrialDialog = false },
        title = { Text("Удалить только пробные копии?") },
        text = { Text("Будут удалены лишь MediaStore-записи, созданные пробой, " +
            "после проверки владельца, папки и содержимого. Исходники, " +
            "скачанные файлы и история не затрагиваются. " +
            "Изменённые или неидентифицированные копии будут пропущены.") },
        confirmButton = {
            TextButton(onClick = {
                removeRepublishTrialDialog = false
                if (!republishTrialBusy && !storageMigrationRunning &&
                    !dateRecoveryBusy && activeDownloadCount == 0)
                    onRemoveGalleryRepublishTestCopies()
            }) { Text("Удалить пробные копии") }
        },
        dismissButton = {
            TextButton(onClick = { removeRepublishTrialDialog = false }) {
                Text("Отмена")
            }
        },
    )

    if (dateTrialDialog) AlertDialog(
        onDismissRequest = { dateTrialDialog = false },
        title = { Text("Пробное восстановление даты") },
        text = { Text("Операция изменит только время изменения ОДНОГО " +
            "сохранённого MediaLoader файла из Pictures/Music. Содержимое и EXIF " +
            "останутся прежними; старое значение записывается для отката. " +
            "После операции проверьте его в галерее Xiaomi. " +
            "Автоматического массового изменения не будет.") },
        confirmButton = {
            TextButton(onClick = {
                dateTrialDialog = false
                if (!dateRecoveryBusy && dateRecoveryReady && activeDownloadCount == 0)
                    onDateRecoveryTrial()
            }) { Text("Проверить на одном файле") }
        },
        dismissButton = {
            TextButton(onClick = { dateTrialDialog = false }) { Text("Отмена") }
        },
    )

    if (migrateLegacyDialog) AlertDialog(
        onDismissRequest = { migrateLegacyDialog = false },
        title = { Text("Перенести старые загрузки?") },
        text = {
            Text("Медиа из Download будут по одному проверены и перенесены: " +
                "фото и видео в Pictures, аудио в Music. История загрузок, " +
                "избранное и альбомы сохранятся. Неподтверждённые и чужие " +
                "файлы останутся на месте. Перенос можно запустить повторно.")
        },
        confirmButton = {
            TextButton(onClick = {
                migrateLegacyDialog = false
                if (!storageMigrationRunning && activeDownloadCount == 0)
                    onMigrateLegacyMedia()
            }) { Text("Начать перенос") }
        },
        dismissButton = {
            TextButton(onClick = { migrateLegacyDialog = false }) { Text("Отмена") }
        },
    )

    if (exifTrialDialog) AlertDialog(
        onDismissRequest = { exifTrialDialog = false },
        title = { Text("Проверить дату EXIF на одной копии?") },
        text = { Text("Будет создана ОДНА отдельная JPEG-копия в " +
            "Pictures/MediaLoader_QA_EXIF_Test. В неё запишется дата ЗАГРУЗКИ " +
            "из истории MediaLoader как условная дата съёмки. Это эксперимент: " +
            "у настоящего фото дата съёмки может быть другой. " +
            "Изображения с уже существующей EXIF-датой пропускаются. " +
            "Оригиналы, их EXIF, история, альбомы и Личное не меняются. " +
            "После проверьте в Xiaomi положение копии и удалите её отдельной кнопкой.") },
        confirmButton = {
            TextButton(onClick = {
                exifTrialDialog = false
                if (!exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                    !storageMigrationRunning && activeDownloadCount == 0) onRunExifTrial()
            }) { Text("Создать одну EXIF-копию") }
        },
        dismissButton = {
            TextButton(onClick = { exifTrialDialog = false }) { Text("Отмена") }
        },
    )

    if (removeExifTrialDialog) AlertDialog(
        onDismissRequest = { removeExifTrialDialog = false },
        title = { Text("Удалить только EXIF-пробу?") },
        text = { Text("Будет удалена только копия из " +
            "MediaLoader_QA_EXIF_Test после проверки владельца, имени, пути и " +
            "контрольной суммы. Оригинальная фотография и база остаются прежними.") },
        confirmButton = {
            TextButton(onClick = {
                removeExifTrialDialog = false
                if (!exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                    !storageMigrationRunning && activeDownloadCount == 0) onRemoveExifTrial()
            }) { Text("Удалить тестовую копию") }
        },
        dismissButton = {
            TextButton(onClick = { removeExifTrialDialog = false }) { Text("Отмена") }
        },
    )
}