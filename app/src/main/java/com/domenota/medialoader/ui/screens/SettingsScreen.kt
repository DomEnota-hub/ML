package com.domenota.medialoader.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CloseFullscreen
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Cookie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import com.domenota.medialoader.data.preferences.VideoPreferences
import com.domenota.medialoader.data.preferences.VideoThumbnailMode
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.domenota.medialoader.data.share.InstagramShareSettings
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.LogExplanation
import com.domenota.medialoader.core.diagnostics.DiagnosticOutcome
import com.domenota.medialoader.core.diagnostics.InstagramSessionLabel
import com.domenota.medialoader.core.diagnostics.InstagramSessionTone
import com.domenota.medialoader.core.recognition.MusicRecognitionMode
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.diagnostics.ActiveSessionDiagnosticProbes
import com.domenota.medialoader.data.search.FullSongSource
import com.domenota.medialoader.ui.components.AppCard
import com.domenota.medialoader.ui.components.DownloadThumbnail
import com.domenota.medialoader.ui.components.SourceIcon
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.MediaKind
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.components.FullSongSearchDialog
import com.domenota.medialoader.ui.model.AdvancedDownloadToolsVisibility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    downloadFolder: String,
    versionName: String,
    versionCode: Int,
    activeDownloadCount: Int = 0,
    instagramSignedIn: Boolean,
    onInstagramSignIn: () -> Unit,
    onInstagramSignOut: () -> Unit,
    onManualSessionId: (String) -> Boolean,
    youtubeSignedIn: Boolean,
    onYouTubeSignIn: () -> Unit,
    onYouTubeSignOut: () -> Unit,
    vkSignedIn: Boolean,
    onVkSignIn: () -> Unit,
    onVkSignOut: () -> Unit,
    rutubeSignedIn: Boolean,
    onRutubeSignIn: () -> Unit,
    onRutubeSignOut: () -> Unit,
    hiddenCount: Int,
    hiddenItems: List<DownloadUiItem>,
    editorItems: List<DownloadUiItem> = emptyList(),
    onEditFile: (String) -> Unit = {},
    onOpenEqualizer: () -> Unit = {},
    onChangeFolder: (String) -> Unit,
    onSelectFolder: () -> Unit,
    onRestore: (Set<String>) -> Unit,
    onEmptyTrash: () -> Unit,
    onOpenTrash: () -> Unit = {},
    onClearHistory: () -> Unit,
    ytDlpUpdate: String? = null,
    ytDlpUpdating: Boolean = false,
    onUpdateYtDlp: () -> Unit = {},
    hasYouTubeCookies: Boolean = false,
    onImportYouTubeCookies: (Uri) -> Unit = {},
    onClearYouTubeCookies: () -> Unit = {},
    musicRecognitionMode: MusicRecognitionMode = MusicRecognitionMode.AUTO,
    renameRecognizedTracks: Boolean = true,
    onMusicRecognitionModeChange: (MusicRecognitionMode) -> Unit = {},
    onRenameRecognizedTracksChange: (Boolean) -> Unit = {},
    closePickerAfterDownload: Boolean = false,
    onClosePickerAfterDownloadChange: (Boolean) -> Unit = {},
    internalViewerEnabled: Boolean = true,
    onInternalViewerEnabledChange: (Boolean) -> Unit = {},
    onBulkDownload: () -> Unit = {},
    onVkCommunityImages: () -> Unit = {},
    onOpenDiagnostics: () -> Unit = {},
    onRefreshInstagramSession: () -> Unit = {},
    instagramSessionRevision: Long = 0L,
    musicSource: FullSongSource = FullSongSource.HITMOZ,
    onMusicSourceChange: (FullSongSource) -> Unit = {},
) {
    val context = LocalContext.current
    var editorKind by remember { mutableStateOf<MediaKind?>(null) }
    var fullSongSearchOpen by remember { mutableStateOf(false) }
    var mediaManagementGranted by remember {
        mutableStateOf(Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context))
    }
    var instagramVerification by remember { mutableStateOf<DiagnosticOutcome?>(null) }
    var instagramMediaVerified by remember { mutableStateOf(false) }
    var instagramVerificationRevision by remember { mutableStateOf(0) }
    LaunchedEffect(instagramSignedIn, instagramVerificationRevision, instagramSessionRevision) {
        val repository = MediaRepository.get(context)
        val hasSession = repository.isLoggedIn()
        instagramMediaVerified = repository.sessions.hasWorkingMediaSession()
        if (hasSession != instagramSignedIn) onRefreshInstagramSession()
        instagramVerification = null
        if (hasSession && instagramVerificationRevision > 0) instagramVerification = ActiveSessionDiagnosticProbes
            .probes(context.applicationContext, repository).first().check()
    }
    val instagramLabel = InstagramSessionLabel.from(instagramSignedIn, instagramVerification,
        instagramMediaVerified)
    val gestureScope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val prefs = remember(context) { context.getSharedPreferences("ui", 0) }
    val videoPreferences = remember(context) { VideoPreferences(context) }
    var thumbnailMode by remember { mutableStateOf(videoPreferences.thumbnailMode) }
    var preferredHeight by remember { mutableStateOf(videoPreferences.preferredHeight) }
    var qualityExpanded by rememberSaveable { mutableStateOf(false) }
    var videoThumbnailDialog by remember { mutableStateOf(false) }
    // Controlled by MediaLoaderApp. No separate local preference copy can go stale.
    var advancedDownloadToolsVisible by remember {
        mutableStateOf(prefs.getBoolean("bulk_visible", false))
    }
    var automaticInstagram by remember { mutableStateOf(InstagramShareSettings.isAutomatic(context)) }
    var notificationsEnabled by remember { mutableStateOf(prefs.getBoolean("notifications", true)) }
    var systemNotificationsAllowed by remember { mutableStateOf(false) }

    fun allowed(): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled() &&
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED)

    DisposableEffect(lifecycleOwner) {
        systemNotificationsAllowed = allowed()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                systemNotificationsAllowed = allowed()
                mediaManagementGranted = Build.VERSION.SDK_INT >= 31 && MediaStore.canManageMedia(context)
                instagramMediaVerified = MediaRepository.get(context).sessions.hasWorkingMediaSession()
                onRefreshInstagramSession()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        systemNotificationsAllowed = allowed()
        if (systemNotificationsAllowed) {
            notificationsEnabled = true
            prefs.edit().putBoolean("notifications", true).apply()
        }
    }
    val cookiesPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportYouTubeCookies(uri)
    }
    var explainLog by remember { mutableStateOf(prefs.getBoolean("log_explanations", false)) }
    var exportLogExplanations by remember { mutableStateOf(prefs.getBoolean("log_export_explanations", false)) }
    var logText by remember { mutableStateOf("") }
    fun refreshAppLog() {
        gestureScope.launch {
            logText = withContext(Dispatchers.IO) { AppLog.readText() }
        }
    }
    val logSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) {
            gestureScope.launch {
                // Extended logs can be ~20 MB; never read and export them on the UI thread.
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)
                            ?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
                                writer.write(AppLog.exportMarkdown(includeExplanations = exportLogExplanations))
                            } ?: error("Не удалось открыть файл для записи")
                    }
                }
                result.onSuccess {
                    Toast.makeText(context, "Лог сохранён", Toast.LENGTH_SHORT).show()
                }.onFailure { error ->
                    AppLog.e("Logs", "Log export failed: ${error.message}", error)
                    Toast.makeText(context, "Не удалось сохранить лог", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemNotificationsAllowed = allowed()
        if (granted) {
            notificationsEnabled = true
            prefs.edit().putBoolean("notifications", true).apply()
        } else {
            notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
    }

    fun enableNotifications() {
        if (allowed()) {
            notificationsEnabled = true
            prefs.edit().putBoolean("notifications", true).apply()
        } else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            notificationSettings.launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            })
        }
    }

    var themeMode by remember { mutableStateOf(prefs.getString("theme", null) ?:
        if (prefs.getBoolean("dark", false)) "dark" else "light") }
    var themeDialog by remember { mutableStateOf(false) }
    var recognitionDialog by remember { mutableStateOf(false) }
    var musicSourceDialog by remember { mutableStateOf(false) }
    var instagramModeDialog by remember { mutableStateOf(false) }
    var spareAuthorizationDialog by remember { mutableStateOf(false) }
    var folderDialog by remember { mutableStateOf(false) }
    var folderName by remember { mutableStateOf("MediaLoader") }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmInstagramSignOut by remember { mutableStateOf(false) }
    var confirmYouTubeSignOut by remember { mutableStateOf(false) }
    var confirmVkSignOut by remember { mutableStateOf(false) }
    var confirmRutubeSignOut by remember { mutableStateOf(false) }
    var trashDialog by remember { mutableStateOf(false) }
    var confirmEmptyTrash by remember { mutableStateOf(false) }
    var selectedTrashIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var sessionDialog by remember { mutableStateOf(false) }
    var sessionValue by remember { mutableStateOf("") }
    var sessionInvalid by remember { mutableStateOf(false) }
    var logsDialog by remember { mutableStateOf(false) }
    var confirmClearAppLog by remember { mutableStateOf(false) }
    var extendedLog by remember(context) { mutableStateOf(AppLog.isExtended()) }
    var developerVisible by remember {
        mutableStateOf(prefs.getBoolean("developer_visible", prefs.getBoolean("logs_unlocked", false)))
    }
    var aboutTapCount by remember { mutableStateOf(0) }
    var aboutTapDeadline by remember { mutableStateOf(0L) }
    var aboutUnlockArmedUntil by remember { mutableStateOf(0L) }

    fun registerAboutTap() {
        val now = SystemClock.elapsedRealtime()
        aboutTapCount = if (now <= aboutTapDeadline) aboutTapCount + 1 else 1
        aboutTapDeadline = now + 900L
        if (aboutTapCount >= 5) {
            aboutTapCount = 0
            aboutUnlockArmedUntil = now + 8_000L
        }
    }

    fun toggleDeveloperModeIfArmed() {
        val now = SystemClock.elapsedRealtime()
        if (now > aboutUnlockArmedUntil) {
            aboutUnlockArmedUntil = 0L
            aboutTapCount = 0
            return
        }
        developerVisible = !developerVisible
        aboutUnlockArmedUntil = 0L
        aboutTapCount = 0
        prefs.edit()
            .putBoolean("developer_visible", developerVisible)
            .remove("logs_unlocked")
            .apply()
        AppLog.i("Settings", if (developerVisible) "Developer section enabled" else "Developer section disabled")
        Toast.makeText(
            context,
            if (developerVisible) "Режим для разработки включён" else "Режим для разработки выключен",
            Toast.LENGTH_SHORT,
        ).show()
    }

    val aboutGestureModifier = Modifier.pointerInput(developerVisible) {
        detectTapGestures(
            onTap = { registerAboutTap() },
            onPress = {
                val hold = gestureScope.launch {
                    delay(3_000)
                    toggleDeveloperModeIfArmed()
                }
                tryAwaitRelease()
                hold.cancel()
            },
        )
    }

    if (confirmInstagramSignOut) AlertDialog(
        onDismissRequest = { confirmInstagramSignOut = false },
        title = { Text("Аккаунт Instagram") },
        text = { Text("Проверьте сохранённую сессию ещё раз или выйдите из аккаунта. Выход удалит её из приложения.") },
        confirmButton = {
            TextButton(onClick = {
                instagramVerificationRevision++
                confirmInstagramSignOut = false
            }) { Text("Проверить") }
        },
        dismissButton = {
            TextButton(onClick = { onInstagramSignOut(); confirmInstagramSignOut = false }) { Text("Выйти") }
        },
    )

    if (confirmYouTubeSignOut) AlertDialog(
        onDismissRequest = { confirmYouTubeSignOut = false },
        title = { Text("Выйти из YouTube?") },
        text = { Text("Сохранённая для yt-dlp сессия YouTube будет удалена.") },
        confirmButton = {
            TextButton(onClick = { onYouTubeSignOut(); confirmYouTubeSignOut = false }) { Text("Выйти") }
        },
        dismissButton = { TextButton(onClick = { confirmYouTubeSignOut = false }) { Text("Отмена") } },
    )

    if (confirmVkSignOut) AlertDialog(
        onDismissRequest = { confirmVkSignOut = false },
        title = { Text("Выйти из VK?") },
        text = { Text("Сессия загрузчика и cookies ВК во встроенном браузере будут удалены. Сессии других источников сохранятся.") },
        confirmButton = {
            TextButton(onClick = { onVkSignOut(); confirmVkSignOut = false }) { Text("Выйти") }
        },
        dismissButton = { TextButton(onClick = { confirmVkSignOut = false }) { Text("Отмена") } },
    )

    if (confirmRutubeSignOut) AlertDialog(
        onDismissRequest = { confirmRutubeSignOut = false },
        title = { Text("Выйти из RUTUBE?") },
        text = { Text("Сохранённая веб-сессия RUTUBE будет удалена.") },
        confirmButton = {
            TextButton(onClick = { onRutubeSignOut(); confirmRutubeSignOut = false }) { Text("Выйти") }
        },
        dismissButton = { TextButton(onClick = { confirmRutubeSignOut = false }) { Text("Отмена") } },
    )

    if (sessionDialog) AlertDialog(
        onDismissRequest = { sessionValue = ""; sessionDialog = false },
        title = { Text("Ввести sessionid") },
        text = {
            Column {
                Text("Это секретный ключ доступа к аккаунту. Вставляй только свой sessionid и никому его не отправляй. Работу сессии проверим по ссылке после сохранения.")
                OutlinedTextField(
                    value = sessionValue,
                    onValueChange = { sessionValue = it; sessionInvalid = false },
                    label = { Text("sessionid") },
                    visualTransformation = PasswordVisualTransformation(),
                    isError = sessionInvalid,
                    singleLine = true,
                )
                if (sessionInvalid) Text("Проверь формат sessionid", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onManualSessionId(sessionValue)) {
                    sessionValue = ""
                    sessionDialog = false
                    instagramVerificationRevision++
                } else {
                    sessionInvalid = true
                }
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = { sessionValue = ""; sessionDialog = false }) { Text("Отмена") }
        },
    )

    if (folderDialog) AlertDialog(
        onDismissRequest = { folderDialog = false },
        title = { Text("Папка загрузок") },
        text = {
            Column {
                Text("Выберите папку через проводник или сохраняйте в Загрузки.")
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { folderDialog = false; onSelectFolder() }) { Text("Выбрать в проводнике") }
                OutlinedTextField(
                    value = folderName,
                    onValueChange = { folderName = it },
                    label = { Text("Папка внутри Загрузок") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onChangeFolder(folderName); folderDialog = false }) {
                Text("Сохранить в Загрузки")
            }
        },
        dismissButton = { TextButton(onClick = { folderDialog = false }) { Text("Отмена") } },
    )

    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text("Очистить историю?") },
        text = { Text("Будут удалены только записи обычной истории загрузок. Скачанные файлы и корзина останутся без изменений.") },
        confirmButton = {
            TextButton(onClick = { onClearHistory(); confirmClear = false }) { Text("Очистить") }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
    )


    if (logsDialog) Dialog(
        onDismissRequest = { logsDialog = false },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.88f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Text("Журнал приложения", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    "События хранятся локально. Фильтры и режим просмотра не изменяют исходный журнал.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                AppLogViewer(
                    rawText = logText,
                    readable = explainLog,
                    onReadableChange = { value ->
                        explainLog = value
                        prefs.edit().putBoolean("log_explanations", value).apply()
                    },
                    modifier = Modifier.weight(1f),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Пояснения в экспорт", modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall)
                    Switch(checked = exportLogExplanations, onCheckedChange = { value ->
                        exportLogExplanations = value
                        prefs.edit().putBoolean("log_export_explanations", value).apply()
                    })
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = ::refreshAppLog) { Text("Обновить") }
                    TextButton(onClick = { confirmClearAppLog = true }) {
                        Text("Очистить", color = MaterialTheme.colorScheme.error)
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { logsDialog = false }) { Text("Закрыть") }
                    TextButton(onClick = {
                        logSaver.launch("MediaLoader_${versionName}_logs.md")
                    }) { Text("Сохранить .md") }
                }
            }
        }
    }

    if (confirmClearAppLog) AlertDialog(
        onDismissRequest = { confirmClearAppLog = false },
        title = { Text("Очистить журнал приложения?") },
        text = {
            Text("Будут удалены технические события и их архивы. " +
                "Скачанные файлы и история загрузок останутся без изменений. " +
                "Перед очисткой можно сохранить журнал в .md.")
        },
        confirmButton = {
            TextButton(onClick = {
                confirmClearAppLog = false
                gestureScope.launch {
                    withContext(Dispatchers.IO) { AppLog.clear() }
                    refreshAppLog()
                }
            }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = {
            TextButton(onClick = { confirmClearAppLog = false }) { Text("Отмена") }
        },
    )

    if (instagramModeDialog) AlertDialog(
        onDismissRequest = { instagramModeDialog = false },
        title = { Text("Режим загрузки Instagram") },
        text = {
            Column {
                listOf(
                    false to ("Ручной" to "Открывать окно выбора фото, видео и музыки"),
                    true to ("Автоматический" to "При передаче ссылки через «Поделиться» сохранять все фото и видео без окна выбора; музыку не извлекать"),
                ).forEach { (automatic, descriptions) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            InstagramShareSettings.setAutomatic(context, automatic)
                            automaticInstagram = automatic
                            instagramModeDialog = false
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = automaticInstagram == automatic,
                            onClick = {
                                InstagramShareSettings.setAutomatic(context, automatic)
                                automaticInstagram = automatic
                                instagramModeDialog = false
                            },
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(descriptions.first, style = MaterialTheme.typography.titleMedium)
                            Text(descriptions.second, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { instagramModeDialog = false }) { Text("Закрыть") } },
    )

    if (spareAuthorizationDialog) AlertDialog(
        onDismissRequest = { spareAuthorizationDialog = false },
        title = { Text("Запасная авторизация") },
        text = {
            Column {
                SettingRow(
                    Icons.Rounded.Key, "Ручной sessionid", "Резервный вход Instagram",
                    onClick = {
                        spareAuthorizationDialog = false
                        sessionInvalid = false
                        sessionDialog = true
                    },
                )
                Spacer(Modifier.height(8.dp))
                SettingRow(
                    Icons.Rounded.Cookie, "Cookies YouTube (резерв)",
                    if (hasYouTubeCookies) "Сессия есть · нажми, чтобы заменить"
                    else "Импорт файла, если вход не сработал",
                    onClick = {
                        spareAuthorizationDialog = false
                        cookiesPicker.launch(arrayOf("text/plain", "application/octet-stream"))
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = { spareAuthorizationDialog = false }) { Text("Закрыть") } },
    )

    if (musicSourceDialog) AlertDialog(
        onDismissRequest = { musicSourceDialog = false },
        title = { Text("Источник полной песни") },
        text = {
            Column {
                listOf(
                    FullSongSource.AUTO to ("Автоматически" to "Сначала основной; при ошибке, тайм-ауте или пустой выдаче — резервный"),
                    FullSongSource.HITMOZ to ("Основной" to "Искать только на основном источнике"),
                    FullSongSource.MUZMO to ("Резервный" to "Искать только на резервном источнике"),
                ).forEach { (source, labels) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onMusicSourceChange(source)
                            musicSourceDialog = false
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = musicSource == source, onClick = {
                            onMusicSourceChange(source)
                            musicSourceDialog = false
                        })
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(labels.first, style = MaterialTheme.typography.titleMedium)
                            Text(labels.second, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { musicSourceDialog = false }) { Text("Закрыть") } },
    )

    if (recognitionDialog) AlertDialog(
        onDismissRequest = { recognitionDialog = false },
        title = { Text("Распознавание музыки") },
        text = {
            Column {
                listOf(
                    MusicRecognitionMode.AUTO to ("Автоматически" to "После загрузки определить трек и записать метаданные"),
                    MusicRecognitionMode.MANUAL to ("Только вручную" to "Запускать из меню скачанного MP3"),
                    MusicRecognitionMode.OFF to ("Выключено" to "Не обращаться к сервису распознавания"),
                ).forEach { (mode, labels) ->
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            onMusicRecognitionModeChange(mode)
                            recognitionDialog = false
                        }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = musicRecognitionMode == mode,
                            onClick = {
                                onMusicRecognitionModeChange(mode)
                                recognitionDialog = false
                            },
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(labels.first, style = MaterialTheme.typography.titleMedium)
                            Text(
                                labels.second,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { recognitionDialog = false }) { Text("Закрыть") } },
    )

    if (videoThumbnailDialog) AlertDialog(
        onDismissRequest = { videoThumbnailDialog = false },
        title = { Text("Миниатюра видео") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                VideoThumbnailMode.entries.forEach { mode ->
                    val choose = {
                        videoPreferences.thumbnailMode = mode
                        thumbnailMode = mode
                        videoThumbnailDialog = false
                    }
                    Row(Modifier.fillMaxWidth().clickable(onClick = choose).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = thumbnailMode == mode, onClick = choose)
                        Text(mode.label, Modifier.padding(start = 8.dp))
                    }
                }
                Text("Если обложка источника недоступна, используем кадр из видео. Настройка действует и на ранее скачанные видео.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { videoThumbnailDialog = false }) { Text("Закрыть") } },
    )

    if (themeDialog) AlertDialog(
        onDismissRequest = { themeDialog = false },
        title = { Text("Тема") },
        text = { Column {
            listOf("light" to "Светлая", "dark" to "Тёмная", "system" to "Системная").forEach { (key, title) ->
                TextButton(onClick = {
                    themeMode = key
                    prefs.edit().putString("theme", key).apply()
                    themeDialog = false
                    (context as? Activity)?.recreate()
                }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (themeMode == key) "✓  $title" else title)
                }
            }
        } },
        confirmButton = { TextButton(onClick = { themeDialog = false }) { Text("Закрыть") } },
    )

    if (fullSongSearchOpen) {
        FullSongSearchDialog(
            source = musicSource,
            initialArtist = "",
            initialTitle = "",
            recognizedArtworkUrl = null,
            recognitionMessage = null,
            onDismiss = { fullSongSearchOpen = false },
        )
    }

    editorKind?.let { kind ->
        val choices = editorItems.filter { it.kind == kind &&
            it.state == DownloadUiState.COMPLETED && it.savedUri != null }
            .distinctBy { it.id }
        AlertDialog(
            onDismissRequest = { editorKind = null },
            title = { Text(when (kind) {
                MediaKind.IMAGE -> "Выбрать фото"
                MediaKind.VIDEO -> "Выбрать видео"
                MediaKind.AUDIO -> "Выбрать аудио"
            }) },
            text = {
                if (choices.isEmpty()) {
                    Text("Подходящих файлов в доступной медиатеке пока нет.")
                } else {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        choices.take(80).forEach { file ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    editorKind = null
                                    onEditFile(file.id)
                                }.padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                DownloadThumbnail(file.savedUri, file.previewUrl, file.kind,
                                    Modifier.size(48.dp))
                                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                    Text(file.title, maxLines = 2,
                                        style = MaterialTheme.typography.bodyMedium)
                                    Text(file.subtitle, maxLines = 1,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { editorKind = null }) { Text("Закрыть") } },
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(horizontal = 20.dp, vertical = 24.dp)) {
        Text("Настройки", modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).pointerInput(Unit) {
            detectTapGestures(onPress = {
                val longPress = gestureScope.launch {
                    delay(3_000)
                    advancedDownloadToolsVisible = !advancedDownloadToolsVisible
                    prefs.edit().putBoolean("bulk_visible", advancedDownloadToolsVisible).apply()
                    Toast.makeText(context, if (advancedDownloadToolsVisible) {
                        "Дополнительные загрузки открыты"
                    } else {
                        "Дополнительные загрузки скрыты"
                    }, Toast.LENGTH_SHORT).show()
                }
                tryAwaitRelease()
                longPress.cancel()
            })
        }, style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(22.dp))
        Text("Редактирование", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditorShortcut("Фото", Icons.Rounded.PhotoCamera, Color(0xFF45B9D5),
                Modifier.weight(1f)) { editorKind = MediaKind.IMAGE }
            EditorShortcut("Видео", Icons.Rounded.VideoLibrary, Color(0xFF549CFC),
                Modifier.weight(1f)) { editorKind = MediaKind.VIDEO }
            EditorShortcut("Аудио", Icons.Rounded.MusicNote, Color(0xFFE58FB6),
                Modifier.weight(1f)) { editorKind = MediaKind.AUDIO }
        }

        Spacer(Modifier.height(26.dp))
        Text("Источники и загрузка", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingRow(Icons.Rounded.PhotoCamera, "Аккаунт Instagram",
                instagramLabel.text,
                brand = "instagram", statusColor = when (instagramLabel.tone) {
                    InstagramSessionTone.SUCCESS -> successColor()
                    InstagramSessionTone.WARNING -> Color(0xFFF29A27)
                    InstagramSessionTone.ERROR -> MaterialTheme.colorScheme.error
                    InstagramSessionTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
                },
                grouped = true,
                onClick = if (instagramSignedIn) ({ confirmInstagramSignOut = true }) else onInstagramSignIn)
            GroupDivider()
            SettingRow(Icons.Rounded.PlayCircle, "Аккаунт YouTube",
                if (youtubeSignedIn) "Подключён" else "Не подключён",
                brand = "youtube", statusColor = if (youtubeSignedIn) successColor() else MaterialTheme.colorScheme.error,
                grouped = true,
                onClick = if (youtubeSignedIn) ({ confirmYouTubeSignOut = true }) else onYouTubeSignIn)
            GroupDivider()
            SettingRow(Icons.Rounded.VideoLibrary, "Аккаунт VK",
                if (vkSignedIn) "Подключён" else "Не подключён",
                brand = "vk", statusColor = if (vkSignedIn) successColor() else MaterialTheme.colorScheme.error,
                grouped = true,
                onClick = if (vkSignedIn) ({ confirmVkSignOut = true }) else onVkSignIn)
            GroupDivider()
            SettingRow(Icons.Rounded.PlayCircle, "Аккаунт RUTUBE",
                if (rutubeSignedIn) "Подключен" else "Не подключён",
                brand = "rutube", statusColor = if (rutubeSignedIn) successColor()
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                grouped = true,
                onClick = if (rutubeSignedIn) ({ confirmRutubeSignOut = true }) else onRutubeSignIn)
            GroupDivider()
            SettingRow(Icons.Rounded.Key, "Запасная авторизация",
                "sessionid Instagram · Cookies YouTube", grouped = true,
                iconTone = SettingIconTone.PURPLE,
                onClick = { spareAuthorizationDialog = true })
        }

        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingRow(Icons.Rounded.Folder, "Папка загрузок", downloadFolder,
                grouped = true, iconTone = SettingIconTone.FOLDER,
                onClick = {
                    folderName = downloadFolder.substringAfter("Download/", "MediaLoader")
                    folderDialog = true
                })
            GroupDivider()
            SettingRow(Icons.Rounded.HighQuality, "Приоритетное качество", VideoPreferences.qualityLabel(preferredHeight),
                grouped = true, iconTone = SettingIconTone.BLUE,
                onClick = { qualityExpanded = !qualityExpanded },
                trailing = { Icon(if (qualityExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (qualityExpanded) "Свернуть качество" else "Выбрать качество") })
            if (qualityExpanded) {
                Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())
                    .padding(start = 48.dp, end = 16.dp, bottom = 12.dp)) {
                    Text("YouTube, VK и Rutube. Если выбранного качества нет — ближайшее ниже, иначе минимальное доступное.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    VideoPreferences.QUALITY_OPTIONS.forEach { height ->
                        val choose = {
                            videoPreferences.preferredHeight = height
                            preferredHeight = height
                            qualityExpanded = false
                        }
                        Row(Modifier.fillMaxWidth().clickable(onClick = choose), verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = preferredHeight == height, onClick = choose)
                            Text(VideoPreferences.qualityLabel(height), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            GroupDivider()
            SettingRow(Icons.Rounded.Notifications, "Уведомления",
                if (!systemNotificationsAllowed) "Разрешить в настройках Android" else "О завершении загрузок",
                grouped = true, iconTone = SettingIconTone.NOTIFICATIONS,
                onClick = {
                    if (notificationsEnabled && systemNotificationsAllowed) {
                        notificationsEnabled = false
                        prefs.edit().putBoolean("notifications", false).apply()
                    } else enableNotifications()
                },
                trailing = { Switch(checked = notificationsEnabled && systemNotificationsAllowed,
                    onCheckedChange = { enabled ->
                        if (enabled) enableNotifications() else {
                            notificationsEnabled = false
                            prefs.edit().putBoolean("notifications", false).apply()
                        }
                    }) })
            GroupDivider()
            SettingRow(Icons.Rounded.Bolt, "Режим загрузки Instagram",
                if (automaticInstagram) "Автоматический" else "Ручной",
                grouped = true, iconTone = SettingIconTone.INSTAGRAM,
                onClick = { instagramModeDialog = true })
            GroupDivider()
            SettingRow(Icons.Rounded.CloseFullscreen, "Закрывать окно после выбора файла",
                "После добавления в загрузки", grouped = true, iconTone = SettingIconTone.CLOSE_PICKER,
                onClick = { onClosePickerAfterDownloadChange(!closePickerAfterDownload) },
                trailing = {
                    Switch(checked = closePickerAfterDownload,
                        onCheckedChange = onClosePickerAfterDownloadChange)
                })
            if (AdvancedDownloadToolsVisibility.showBulkLinks(advancedDownloadToolsVisible)) {
                GroupDivider()
                SettingRow(Icons.Rounded.PlaylistAddCheck, "Массовая загрузка",
                    "Импорт TXT и выбор файлов", grouped = true,
                    iconTone = SettingIconTone.BULK, onClick = onBulkDownload)
            }
            if (AdvancedDownloadToolsVisibility.showVkCommunity(advancedDownloadToolsVisible)) {
                GroupDivider()
                SettingRow(Icons.Rounded.Groups, "Загрузка из сообщества VK",
                    "Поиск и выбор изображений со стены", grouped = true,
                    iconTone = SettingIconTone.BLUE, onClick = onVkCommunityImages)
            }
        }

        Spacer(Modifier.height(26.dp))
        Text("Музыка", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingRow(Icons.Rounded.MusicNote, "Найти полную песню",
                "Поиск по названию и исполнителю",
                grouped = true, iconTone = SettingIconTone.MUSIC,
                onClick = { fullSongSearchOpen = true })
            GroupDivider()
            SettingRow(Icons.Rounded.GraphicEq, "Эквалайзер",
                "Настройка звука для всей музыки",
                grouped = true, iconTone = SettingIconTone.PURPLE,
                onClick = onOpenEqualizer)
            GroupDivider()
            SettingRow(Icons.Rounded.MusicNote, "Источник полной песни",
                when (musicSource) {
                    FullSongSource.AUTO -> "Автоматически"
                    FullSongSource.HITMOZ -> "Основной"
                    FullSongSource.MUZMO -> "Резервный"
                },
                grouped = true, iconTone = SettingIconTone.MUSIC,
                onClick = { musicSourceDialog = true })
            GroupDivider()
            SettingRow(Icons.Rounded.GraphicEq, "Распознавание музыки",
                when (musicRecognitionMode) {
                    MusicRecognitionMode.AUTO -> "Автоматически после загрузки"
                    MusicRecognitionMode.MANUAL -> "Только вручную"
                    MusicRecognitionMode.OFF -> "Выключено"
                },
                grouped = true, iconTone = SettingIconTone.RECOGNITION,
                onClick = { recognitionDialog = true })
            GroupDivider()
            SettingRow(Icons.Rounded.DriveFileRenameOutline, "Переименовывать найденные треки",
                "Исполнитель — Название.mp3", grouped = true,
                iconTone = if (musicRecognitionMode == MusicRecognitionMode.OFF)
                    SettingIconTone.NEUTRAL else SettingIconTone.RENAME,
                onClick = if (musicRecognitionMode == MusicRecognitionMode.OFF) null else ({
                    onRenameRecognizedTracksChange(!renameRecognizedTracks)
                }),
                trailing = {
                    Switch(
                        checked = renameRecognizedTracks,
                        enabled = musicRecognitionMode != MusicRecognitionMode.OFF,
                        onCheckedChange = onRenameRecognizedTracksChange,
                    )
                })
        }

        Spacer(Modifier.height(26.dp))
        Text("Интерфейс", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            SettingRow(Icons.Rounded.Palette, "Тема", "Светлая, тёмная или системная",
                grouped = true, iconTone = SettingIconTone.THEME, onClick = { themeDialog = true })
            GroupDivider()
            SettingRow(Icons.Rounded.VideoLibrary, "Миниатюра видео", thumbnailMode.label,
                grouped = true, iconTone = SettingIconTone.PLAYER, onClick = { videoThumbnailDialog = true })
            GroupDivider()
            SettingRow(
                Icons.Rounded.SmartDisplay,
                "Встроенный проигрыватель",
                "Открывать видео, музыку и фото внутри приложения",
                grouped = true,
                iconTone = SettingIconTone.PLAYER,
                onClick = { onInternalViewerEnabledChange(!internalViewerEnabled) },
                trailing = {
                    Switch(
                        checked = internalViewerEnabled,
                        onCheckedChange = onInternalViewerEnabledChange,
                    )
                },
            )
        }

        Spacer(Modifier.height(26.dp))
        Text("Данные и обслуживание", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(10.dp))
        SettingsGroup {
            if (Build.VERSION.SDK_INT >= 31 && !mediaManagementGranted) {
                SettingRow(Icons.Rounded.DeleteSweep, "Управление медиафайлами",
                    "Разрешить удаление без повторного запроса Android", grouped = true,
                    iconTone = SettingIconTone.TRASH, onClick = {
                        try { context.startActivity(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA,
                            Uri.parse("package:${context.packageName}"))) }
                        catch (_: Exception) { Toast.makeText(context, "Настройка недоступна на этом устройстве", Toast.LENGTH_SHORT).show() }
                    })
                GroupDivider()
            }
            SettingRow(Icons.Rounded.RestoreFromTrash, "Корзина",
                "Удалённые файлы · $hiddenCount", grouped = true,
                iconTone = SettingIconTone.TRASH,
                onClick = onOpenTrash)
            GroupDivider()
            SettingRow(Icons.Rounded.DeleteSweep, "Очистить историю",
                "Удалить список загрузок", grouped = true,
                iconTone = SettingIconTone.CLEAR_HISTORY, onClick = { confirmClear = true })
            GroupDivider()
            SettingRow(Icons.Rounded.SystemUpdate,
                if (ytDlpUpdating) "Обновляем yt-dlp…" else "Обновить yt-dlp",
                ytDlpUpdate ?: "Проверить и установить последнюю версию",
                grouped = true, iconTone = SettingIconTone.YTDLP,
                onClick = if (ytDlpUpdating) null else onUpdateYtDlp)
            GroupDivider()
            SettingRow(
                Icons.Rounded.Info,
                "О приложении",
                "Версия $versionName",
                grouped = true,
                iconTone = SettingIconTone.ABOUT,
                showChevron = false,
                modifier = aboutGestureModifier,
            )
        }

        if (developerVisible) {
            Spacer(Modifier.height(26.dp))
            Text(
                "Для разработки",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SettingsGroup {
                SettingRow(
                    Icons.Rounded.BugReport,
                    "Диагностика",
                    "Состояние приложения и источников",
                    grouped = true,
                    iconTone = SettingIconTone.RED,
                    onClick = onOpenDiagnostics,
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.Article,
                    "Журнал приложения",
                    "Ошибки, предупреждения и события",
                    grouped = true,
                    iconTone = SettingIconTone.BLUE,
                    onClick = { logsDialog = true; refreshAppLog() },
                )
                GroupDivider()
                SettingRow(
                    Icons.Rounded.BugReport,
                    "Расширенный журнал",
                    if (extendedLog) "Подробные события всех подсистем · включён"
                    else "Подробная диагностика · выключена",
                    grouped = true,
                    iconTone = SettingIconTone.AMBER,
                    showChevron = false,
                    trailing = {
                        Switch(
                            checked = extendedLog,
                            onCheckedChange = { enabled ->
                                extendedLog = enabled
                                AppLog.setExtended(enabled)
                            },
                        )
                    },
                    onClick = {
                        extendedLog = !extendedLog
                        AppLog.setExtended(extendedLog)
                    },
                )
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    brand: String? = null,
    statusColor: Color? = null,
    showChevron: Boolean = true,
    grouped: Boolean = false,
    iconTone: SettingIconTone = SettingIconTone.NEUTRAL,
) {
    val tint = iconTone.tint
    val iconBackground = tint.copy(alpha = if (MaterialTheme.colorScheme.background.luminance() > 0.5f) 0.12f else 0.22f)
    val content: @Composable ColumnScope.() -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(modifier)
                .let { base -> if (onClick != null) base.clickable(onClick = onClick) else base }
                .padding(horizontal = 16.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (brand != null) SourceIcon(brand, 44.dp) else Row(
                modifier = Modifier
                    .size(44.dp)
                    .background(iconBackground, RoundedCornerShape(14.dp)),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(23.dp),
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(3.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when {
                trailing != null -> trailing()
                onClick != null && showChevron -> Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (grouped) Column(content = content) else AppCard(Modifier.fillMaxWidth(), content)
}

private enum class SettingIconTone(val tint: Color) {
    PURPLE(Color(0xFF8744ED)),
    BLUE(Color(0xFF2588E8)),
    AMBER(Color(0xFFF29A27)),
    GREEN(Color(0xFF26A84A)),
    RED(Color(0xFFE94854)),
    NEUTRAL(Color(0xFF68728F)),
    FOLDER(Color(0xFF398FF1)),
    NOTIFICATIONS(Color(0xFFF2A43C)),
    INSTAGRAM(Color(0xFFE77D9F)),
    CLOSE_PICKER(Color(0xFF8498DC)),
    BULK(Color(0xFFDE8B65)),
    MUSIC(Color(0xFF28BDAA)),
    RECOGNITION(Color(0xFF4BAEEA)),
    RENAME(Color(0xFF41BD7B)),
    THEME(Color(0xFFA47AF1)),
    PLAYER(Color(0xFF368EE8)),
    TRASH(Color(0xFF4CB98B)),
    CLEAR_HISTORY(Color(0xFFE15A69)),
    YTDLP(Color(0xFF48A6DC)),
    ABOUT(Color(0xFF9098AC)),
}


@Composable
private fun EditorShortcut(
    label: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    AppCard(modifier.clickable(onClick = onClick)) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    AppCard(Modifier.fillMaxWidth(), content)
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outline)
}

@Composable
private fun successColor(): Color = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
    Color(0xFF15964A) else Color(0xFF65D49A)
