package com.domenota.medialoader.ui.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.InsertPhoto
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.SmartDisplay
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
    migrationInventoryStatus: String,
    migrationInventoryBusy: Boolean,
    migrationInventoryReady: Boolean,
    onRunMigrationInventory: () -> Unit,
    onExportMigrationInventory: () -> Unit,
    republishTrialStatus: String,
    republishTrialBusy: Boolean,
    onRunGalleryRepublishTrial: () -> Unit,
    onRemoveGalleryRepublishTestCopies: () -> Unit,
    exifTrialStatus: String,
    exifTrialBusy: Boolean,
    onRunExifTrial: () -> Unit,
    onRemoveExifTrial: () -> Unit,
    videoTrialStatus: String,
    videoTrialBusy: Boolean,
    onRunVideoTrial: () -> Unit,
    onRemoveVideoTrial: () -> Unit,
    sameUriBusy: Boolean,
    sameUriJpegStatus: String,
    sameUriVideoStatus: String,
    onRunSameUriJpeg: () -> Unit,
    onRunSameUriMp4: () -> Unit,
    onRepairSameUriJpegMtime: () -> Unit,
    onRepairSameUriMp4Mtime: () -> Unit,
    onRestoreSameUriJpeg: () -> Unit,
    onRestoreSameUriMp4: () -> Unit,
    onRemoveSameUriJpeg: () -> Unit,
    onRemoveSameUriMp4: () -> Unit,
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
    var undoDateDialog by remember { mutableStateOf(false) }
    var exifTrialDialog by remember { mutableStateOf(false) }
    var removeExifTrialDialog by remember { mutableStateOf(false) }
    var sameUriJpegDialog by remember { mutableStateOf(false) }
    var sameUriMp4Dialog by remember { mutableStateOf(false) }
    var sameUriRestoreDialog by remember { mutableStateOf<Boolean?>(null) }
    var sameUriDeleteDialog by remember { mutableStateOf<Boolean?>(null) }
    var videoTrialDialog by remember { mutableStateOf(false) }
    var removeVideoTrialDialog by remember { mutableStateOf(false) }

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
            Text("Этап 1/5 · инвентаризация перед миграцией. " +
                "Оригиналы, URI и базы не меняются. Дата истории ML = время постановки загрузки в очередь.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow(Icons.Rounded.Article, "Проверить ВСЕ файлы · только чтение",
                if (migrationInventoryBusy) "Проверяем историю, MediaStore, VK, альбомы…"
                else migrationInventoryStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.LOG,
                onClick = {
                    if (!migrationInventoryBusy && !storageMigrationRunning &&
                        !sameUriBusy && activeDownloadCount == 0) onRunMigrationInventory()
                })
            if (migrationInventoryReady) {
                SettingRow(Icons.Rounded.Folder, "Сохранить полный отчёт JSON",
                    "Все записи, блокировки, связи и оценка памяти. Источники URL скрыты.",
                    grouped = true, nested = true, iconTone = SettingIconTone.FILE_ACCESS,
                    onClick = { if (!migrationInventoryBusy) onExportMigrationInventory() })
            }
            Text("Старый перенос Download → Pictures ОТКЛЮЧЁН. " +
                "Массовое пересоздание и изменение дат не доступны до отдельной приёмки.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    if (!sameUriBusy && !exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                        !storageMigrationRunning && activeDownloadCount == 0)
                        exifTrialDialog = true
                })
            SettingRow(Icons.Rounded.DeleteSweep, "Удалить пробу EXIF",
                "Проверка имени, владельца и SHA-256; исходник не трогаем",
                grouped = true, nested = true, iconTone = SettingIconTone.TRASH,
                onClick = {
                    if (!sameUriBusy && !exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                        !storageMigrationRunning && activeDownloadCount == 0)
                        removeExifTrialDialog = true
                })
            GroupDivider()
            Text("Новая проба · дата контейнера MP4 на одной копии",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow(Icons.Rounded.SmartDisplay, "Проверить историческую дату видео",
                if (videoTrialBusy) "Создаём одну MP4-копию…" else videoTrialStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.MEDIA_MANAGEMENT,
                onClick = {
                    if (!sameUriBusy && !videoTrialBusy && !exifTrialBusy && !republishTrialBusy &&
                        !dateRecoveryBusy && !storageMigrationRunning && activeDownloadCount == 0)
                        videoTrialDialog = true
                })
            SettingRow(Icons.Rounded.DeleteSweep, "Удалить пробу MP4",
                "Удаляем только тестовую копию после проверки владельца и SHA-256",
                grouped = true, nested = true, iconTone = SettingIconTone.TRASH,
                onClick = {
                    if (!sameUriBusy && !videoTrialBusy && !exifTrialBusy && !republishTrialBusy &&
                        !dateRecoveryBusy && !storageMigrationRunning && activeDownloadCount == 0)
                        removeVideoTrialDialog = true
                })
            GroupDivider()
            Text("Новый эксперимент · тот же MediaStore URI",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Только ОТДЕЛЬНЫЕ тестовые копии. Никакие реальные загрузки, " +
                "их URI или база ML не меняются. После записи сверяем ID и даты.",
                style = MaterialTheme.typography.bodySmall)
            SettingRow(Icons.Rounded.InsertPhoto, "JPEG: изменение без нового URI",
                if (sameUriBusy) "Выполняется опыт…" else sameUriJpegStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.RECOGNITION,
                onClick = {
                    if (!sameUriBusy && !videoTrialBusy && !exifTrialBusy &&
                        !dateRecoveryBusy && !storageMigrationRunning && activeDownloadCount == 0)
                        sameUriJpegDialog = true
                })
            SettingRow(Icons.Rounded.SmartDisplay, "MP4: изменение без нового URI",
                if (sameUriBusy) "Выполняется опыт…" else sameUriVideoStatus,
                grouped = true, nested = true, iconTone = SettingIconTone.MEDIA_MANAGEMENT,
                onClick = {
                    if (!sameUriBusy && !videoTrialBusy && !exifTrialBusy &&
                        !dateRecoveryBusy && !storageMigrationRunning && activeDownloadCount == 0)
                        sameUriMp4Dialog = true
                })
            Text("Исправление сортировки 1.7.164: меняем только lastModified двух " +
                "УЖЕ СОЗДАННЫХ QA-копий. Содержимое, MediaStore ID, URI, имя и ссылки не меняются.",
                style = MaterialTheme.typography.bodySmall)
            SettingRow(Icons.Rounded.PhotoCamera, "JPEG: исправить сортировку существующей пробы",
                "Старый URI, дата изменения файла + повторный MediaScanner",
                grouped = true, nested = true, iconTone = SettingIconTone.RECOGNITION,
                onClick = {
                    if (!sameUriBusy && !dateRecoveryBusy && !storageMigrationRunning &&
                        activeDownloadCount == 0) onRepairSameUriJpegMtime()
                })
            SettingRow(Icons.Rounded.SmartDisplay, "MP4: исправить сортировку существующей пробы",
                "Старый URI, дата изменения файла + повторный MediaScanner",
                grouped = true, nested = true, iconTone = SettingIconTone.MEDIA_MANAGEMENT,
                onClick = {
                    if (!sameUriBusy && !dateRecoveryBusy && !storageMigrationRunning &&
                        activeDownloadCount == 0) onRepairSameUriMp4Mtime()
                })
            SettingRow(Icons.Rounded.RestoreFromTrash, "Откатить пробу JPEG",
                "Восстановить байты тестовой копии из резерва в тот же URI",
                grouped = true, nested = true, iconTone = SettingIconTone.CLEAR_HISTORY,
                onClick = { if (!sameUriBusy) sameUriRestoreDialog = false })
            SettingRow(Icons.Rounded.RestoreFromTrash, "Откатить пробу MP4",
                "Восстановить тестовый MP4 из резерва в тот же URI",
                grouped = true, nested = true, iconTone = SettingIconTone.CLEAR_HISTORY,
                onClick = { if (!sameUriBusy) sameUriRestoreDialog = true })
            SettingRow(Icons.Rounded.DeleteSweep, "Удалить QA-копию JPEG",
                "Только тестовый альбом после проверки владельца, ID и имени",
                grouped = true, nested = true, iconTone = SettingIconTone.TRASH,
                onClick = { if (!sameUriBusy) sameUriDeleteDialog = false })
            SettingRow(Icons.Rounded.DeleteSweep, "Удалить QA-копию MP4",
                "Только тестовый альбом после проверки владельца, ID и имени",
                grouped = true, nested = true, iconTone = SettingIconTone.TRASH,
                onClick = { if (!sameUriBusy) sameUriDeleteDialog = true })
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
            // Stage 1 audit is read-only. Legacy real-file mtime trial is hidden.
            // Keep rollback available solely for changes made before this QA build.
            Text("Массовая обработка отключена до тестов сохранения URI " +
                "и обязательного аудита 600+ файлов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingRow(Icons.Rounded.Info, "Откат изменения mtime",
                "Вернуть даты из журнала, без удаления файлов",
                grouped = true, nested = true, iconTone = SettingIconTone.MEDIA_MANAGEMENT,
                onClick = {
                    if (!dateRecoveryBusy && activeDownloadCount == 0)
                        undoDateDialog = true
                })
        }
    }

    if (sameUriJpegDialog || sameUriMp4Dialog) {
        val video = sameUriMp4Dialog
        AlertDialog(
            onDismissRequest = { sameUriJpegDialog = false; sameUriMp4Dialog = false },
            title = { Text("Опыту с прежним URI быть? (${if (video) "MP4" else "JPEG"})") },
            text = { Text("Будет создана ОДНА отдельная копия в " +
                "MediaLoader_QA_SameUri_Test, затем её содержимое будет перезаписано " +
                "через ТОТ ЖЕ MediaStore URI. Историческое время берём из базы ML. " +
                "Оригиналы и история загрузок только читаются. " +
                "Если Android сменит ID или появятся дубли, проба будет остановлена. " +
                "После неё проверьте Xiaomi Gallery, выполните откат и удаление копии.") },
            confirmButton = { TextButton(onClick = {
                sameUriJpegDialog = false; sameUriMp4Dialog = false
                if (!sameUriBusy && !dateRecoveryBusy && !storageMigrationRunning &&
                    activeDownloadCount == 0) {
                    if (video) onRunSameUriMp4() else onRunSameUriJpeg()
                }
            }) { Text("Создать и изменить QA-копию") } },
            dismissButton = { TextButton(onClick = {
                sameUriJpegDialog = false; sameUriMp4Dialog = false
            }) { Text("Отмена") } },
        )
    }
    sameUriRestoreDialog?.let { video ->
        AlertDialog(onDismissRequest = { sameUriRestoreDialog = null },
            title = { Text("Откатить пробную ${if (video) "MP4" else "JPEG"}-копию?") },
            text = { Text("Исходные байты пробной копии восстановятся из резервного " +
                "файла по тому же URI. Реальные загрузки и база ML не изменяются.") },
            confirmButton = { TextButton(onClick = {
                sameUriRestoreDialog = null
                if (!sameUriBusy && activeDownloadCount == 0)
                    if (video) onRestoreSameUriMp4() else onRestoreSameUriJpeg()
            }) { Text("Откатить пробу") } },
            dismissButton = { TextButton(onClick = { sameUriRestoreDialog = null }) {
                Text("Отмена") } })
    }
    sameUriDeleteDialog?.let { video ->
        AlertDialog(onDismissRequest = { sameUriDeleteDialog = null },
            title = { Text("Удалить только QA-копию ${if (video) "MP4" else "JPEG"}?") },
            text = { Text("Android удалит только тестовый файл из " +
                "MediaLoader_QA_SameUri_Test после проверки владельца, ID и " +
                "отсутствия ссылок из реальной истории ML.") },
            confirmButton = { TextButton(onClick = {
                sameUriDeleteDialog = null
                if (!sameUriBusy && activeDownloadCount == 0)
                    if (video) onRemoveSameUriMp4() else onRemoveSameUriJpeg()
            }) { Text("Удалить QA-копию") } },
            dismissButton = { TextButton(onClick = { sameUriDeleteDialog = null }) {
                Text("Отмена") } })
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

    if (videoTrialDialog) AlertDialog(
        onDismissRequest = { videoTrialDialog = false },
        title = { Text("Проверить дату MP4 на отдельной копии?") },
        text = { Text("Одно видео до 100 МБ будет скопировано в " +
            "Pictures/MediaLoader_QA_VIDEO_Date_Test. FFmpeg без перекодирования " +
            "поставит дату из истории ML как УСЛОВНУЮ дату создания видео. " +
            "Это не обязательно реальное время съёмки! Сам оригинал, ссылки " +
            "Загрузок, избранное, альбомы и история не изменяются. " +
            "Проверьте дату, порядок, звук и воспроизведение в Xiaomi, " +
            "потом удалите копию отдельной кнопкой.") },
        confirmButton = { TextButton(onClick = {
            videoTrialDialog = false
            if (!sameUriBusy && !videoTrialBusy && !exifTrialBusy && !republishTrialBusy &&
                !storageMigrationRunning && !dateRecoveryBusy && activeDownloadCount == 0)
                onRunVideoTrial()
        }) { Text("Создать одну пробную MP4-копию") } },
        dismissButton = { TextButton(onClick = { videoTrialDialog = false }) {
            Text("Отмена")
        } },
    )

    if (removeVideoTrialDialog) AlertDialog(
        onDismissRequest = { removeVideoTrialDialog = false },
        title = { Text("Удалить только пробный MP4?") },
        text = { Text("Будет удалена только подтверждённая копия из тестового " +
            "альбома после сверки SHA-256, имени, папки и владельца. " +
            "Оригинал и база ML останутся нетронутыми.") },
        confirmButton = { TextButton(onClick = {
            removeVideoTrialDialog = false
            if (!sameUriBusy && !videoTrialBusy && !exifTrialBusy && !republishTrialBusy &&
                !storageMigrationRunning && !dateRecoveryBusy && activeDownloadCount == 0)
                onRemoveVideoTrial()
        }) { Text("Удалить тестовую копию") } },
        dismissButton = { TextButton(onClick = { removeVideoTrialDialog = false }) {
            Text("Отмена")
        } },
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
                if (!sameUriBusy && !exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
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
                if (!sameUriBusy && !exifTrialBusy && !republishTrialBusy && !dateRecoveryBusy &&
                    !storageMigrationRunning && activeDownloadCount == 0) onRemoveExifTrial()
            }) { Text("Удалить тестовую копию") }
        },
        dismissButton = {
            TextButton(onClick = { removeExifTrialDialog = false }) { Text("Отмена") }
        },
    )
}
