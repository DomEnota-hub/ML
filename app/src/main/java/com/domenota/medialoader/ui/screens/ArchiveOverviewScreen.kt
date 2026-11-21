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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Public Archive entry. Only the fully verified physical-folder .nomedia
 * action is currently exposed through the established folder menu.
 * Selective per-file MediaStore moves require real Xiaomi device validation
 * before enabling the user-facing mutation. No misleading "hidden" status. */
@Composable
internal fun ArchiveOverviewScreen(
    knownHiddenFolders: Int,
    onClose: () -> Unit,
    onOpenFolders: () -> Unit,
) {
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
                Text("Для отдельных файлов из общей папки используется независимый " +
                    "движок с журналом, контрольными суммами и проверкой прежних ссылок. " +
                    "Другие файлы физического каталога скрывать нельзя.",
                    style=MaterialTheme.typography.bodySmall)
                Text("Требуется проверка поведения MediaStore на устройстве. " +
                    "До неё выборочное скрытие недоступно.",
                    style=MaterialTheme.typography.labelMedium,
                    color=MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick={},enabled=false,
                    modifier=Modifier.fillMaxWidth().testTag("archive-selective-locked")) {
                    Text("Выборочное скрытие пока недоступно")
                }
            }
        }
    }
}
