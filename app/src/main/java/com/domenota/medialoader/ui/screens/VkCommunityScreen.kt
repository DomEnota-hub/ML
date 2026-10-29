package com.domenota.medialoader.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DateRange
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import com.domenota.medialoader.data.vkcommunity.VkCommunityController
import com.domenota.medialoader.data.vkcommunity.VkCommunityApi
import com.domenota.medialoader.data.vkcommunity.VkFavoriteStore
import com.domenota.medialoader.data.vkcommunity.VkFavorite
import com.domenota.medialoader.data.vkcommunity.VkFavoriteCheckBatch
import com.domenota.medialoader.data.vkcommunity.VkCommunityJob
import com.domenota.medialoader.data.vkcommunity.VkCommunityLink
import com.domenota.medialoader.data.vkcommunity.VkDatePeriod
import com.domenota.medialoader.data.vkcommunity.VkJobPhase
import com.domenota.medialoader.data.vkcommunity.VkItemPhase
import com.domenota.medialoader.data.vkcommunity.VkResultPosition
import com.domenota.medialoader.data.vkcommunity.VkStoredImage
import com.domenota.medialoader.ui.components.RemotePreview
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext

@Composable
fun VkCommunityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val controller = remember(context) { VkCommunityController(context.applicationContext) }
    val communityApi = remember { VkCommunityApi() }
    val favoritesStore = remember(context) { VkFavoriteStore(context.applicationContext) }
    val prefs = remember(context) { context.getSharedPreferences("vk_community_ui", 0) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var tab by remember { mutableIntStateOf(0) }
    var link by remember { mutableStateOf(prefs.getString("community_link", "").orEmpty()) }
    var period by remember { mutableStateOf(runCatching {
        VkDatePeriod.of(
            LocalDate.parse(prefs.getString("period_start", "")),
            LocalDate.parse(prefs.getString("period_end", "")),
        )
    }.getOrElse { VkDatePeriod.lastDays(7, today) }) }
    var showPeriod by remember { mutableStateOf(false) }
    var tokenDraft by remember { mutableStateOf("") }
    var tokenPresent by remember { mutableStateOf(false) }
    var tokenVerified by remember { mutableStateOf(prefs.getBoolean("key_verified", false)) }
    var tokenError by remember { mutableStateOf(prefs.getBoolean("key_error", false)) }
    var confirmTokenDelete by remember { mutableStateOf(false) }
    var confirmTokenReplace by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var jobId by remember { mutableStateOf<String?>(null) }
    var activeJobId by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<VkCommunityJob?>(null) }
    var visibleImages by remember { mutableStateOf<List<VkStoredImage>>(emptyList()) }
    var hasPendingSelection by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf("setup") }
    var readOnly by remember { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(favoritesStore.list()) }
    var favoriteName by remember { mutableStateOf("") }
    var favoriteAvatarUrl by remember { mutableStateOf<String?>(null) }
    var favoriteLoading by remember { mutableStateOf(false) }
    var favoriteLookupLink by remember { mutableStateOf("") }
    var showFavoriteDialog by remember { mutableStateOf(false) }
    var favoriteToRemove by remember { mutableStateOf<VkFavorite?>(null) }
    var favoriteDateTarget by remember { mutableStateOf<VkFavorite?>(null) }
    var favoriteBatch by remember { mutableStateOf<VkFavoriteCheckBatch?>(controller.favoriteChecks.current()) }
    var favoriteJobs by remember { mutableStateOf<Map<String, VkCommunityJob>>(emptyMap()) }
    var returnToChecks by remember { mutableStateOf(false) }
    var reviewError by remember { mutableStateOf<String?>(null) }
    var journal by remember { mutableStateOf<List<VkCommunityJob>>(emptyList()) }
    var deleteJobId by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val token = withContext(Dispatchers.IO) { controller.tokens.read() }
        tokenPresent = token != null
        activeJobId = withContext(Dispatchers.IO) {
            val checkIds = controller.favoriteChecks.current()?.entries.orEmpty().map { it.jobId }.toSet()
            controller.database.jobs().firstOrNull { it.id !in checkIds }?.id
        }
        jobId = activeJobId
        val missing = favoritesStore.list().filter { favorite ->
            favorite.avatarUrl == null ||
                favorite.label.equals(favorite.link.substringAfterLast('/'), ignoreCase = true)
        }
        if (token != null && missing.isNotEmpty()) {
            val infos = withTimeoutOrNull(10_000L) { withContext(Dispatchers.IO) {
                runCatching { communityApi.communityInfos(missing.mapNotNull {
                    VkCommunityLink.parse(it.link)
                }, token) }.getOrDefault(emptyMap())
            } }
            if (!infos.isNullOrEmpty()) favorites = favoritesStore.enrich(infos)
        }
    }
    LaunchedEffect(jobId, page) {
        while (true) {
            val id = jobId
            if (id != null) {
                val snapshot = withContext(Dispatchers.IO) {
                    Triple(controller.database.job(id), controller.database.images(id,
                        page * VkResultPosition.PAGE_SIZE, VkResultPosition.PAGE_SIZE),
                        controller.database.nextDownloads(id, 1).isNotEmpty())
                }
                job = snapshot.first
                visibleImages = snapshot.second
                hasPendingSelection = snapshot.third
                tokenVerified = prefs.getBoolean("key_verified", false)
                tokenError = prefs.getBoolean("key_error", false)
            }
            delay(1_000)
        }
    }
    LaunchedEffect(jobId, view, job?.scanComplete, readOnly) {
        val id = jobId
        if (view == "results" && id != null && job?.scanComplete == true && !readOnly &&
            job?.phase !in setOf(VkJobPhase.DOWNLOADING, VkJobPhase.SCANNING)) {
            withContext(Dispatchers.IO) { controller.reconcileMissingSelected(id) }
        }
    }
    LaunchedEffect(view, favoriteBatch?.id) {
        while (view == "checks") {
            val snapshot = withContext(Dispatchers.IO) {
                val batch = controller.favoriteChecks.current()
                Triple(batch, batch?.entries.orEmpty().mapNotNull { entry ->
                    controller.database.job(entry.jobId)?.let { entry.jobId to it }
                }.toMap(), favoritesStore.list())
            }
            favoriteBatch = snapshot.first
            favoriteJobs = snapshot.second
            favorites = snapshot.third
            delay(1_000)
        }
    }
    if (showPeriod) VkPeriodPicker(period, onApply = {
        period = it
        prefs.edit().putString("period_start", it.start.toString())
            .putString("period_end", it.endInclusive.toString()).apply()
        showPeriod = false
    }, onDismiss = { showPeriod = false })
    favoriteDateTarget?.let { target ->
        val selected = runCatching { LocalDate.parse(target.sinceDate) }.getOrDefault(today)
        VkPeriodPicker(VkDatePeriod.of(selected, selected), onApply = { chosen ->
            favoritesStore.setSinceDate(target.key, chosen.start)
            favorites = favoritesStore.list()
            favoriteDateTarget = null
        }, onDismiss = { favoriteDateTarget = null }, singleDate = true)
    }
    reviewError?.let { detail -> AlertDialog(
        onDismissRequest = { reviewError = null },
        title = { Text("Просмотр не завершён") },
        text = { Text(detail) },
        confirmButton = { TextButton(onClick = { reviewError = null }) { Text("Понятно") } },
    ) }
    if (showFavoriteDialog) AlertDialog(
        onDismissRequest = { showFavoriteDialog = false }, title = { Text("Добавить в избранное") },
        text = { Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                favoriteAvatarUrl?.let { avatar ->
                    RemotePreview(avatar, Modifier.size(48.dp).clip(CircleShape), "vk")
                }
                OutlinedTextField(favoriteName, { favoriteName = it },
                    label = { Text("Название сообщества") }, modifier = Modifier.weight(1f))
            }
            if (favoriteLoading) Text("Получаем данные сообщества…",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(enabled = !favoriteLoading, onClick = {
            favoritesStore.save(favoriteLookupLink, favoriteName, favoriteAvatarUrl)
            favorites = favoritesStore.list()
            showFavoriteDialog = false
        }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = { showFavoriteDialog = false }) { Text("Отмена") } },
    )
    favoriteToRemove?.let { favorite -> AlertDialog(
        onDismissRequest = { favoriteToRemove = null },
        title = { Text("Удалить из избранного?") },
        text = { Text("Сообщество «${favorite.label}» исчезнет из избранного. Поиски и загруженные файлы сохранятся.") },
        confirmButton = { TextButton(onClick = {
            favoritesStore.remove(favorite.key)
            favorites = favoritesStore.list()
            favoriteToRemove = null
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { favoriteToRemove = null }) { Text("Отмена") } },
    ) }
    deleteJobId?.let { target -> AlertDialog(
        onDismissRequest = { deleteJobId = null }, title = { Text("Удалить запись журнала?") },
        text = { Text("Список найденных изображений будет удалён. Скачанные файлы сохранятся.") },
        confirmButton = { TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                val deleted = controller.deleteHistoryJob(target)
                val updated = controller.database.jobs()
                withContext(Dispatchers.Main) {
                    journal = updated
                    if (!deleted) message = "Активное задание нельзя удалить"
                    if (activeJobId == target && deleted) activeJobId = null
                    if (jobId == target && deleted) { jobId = null; job = null; visibleImages = emptyList() }
                    deleteJobId = null
                }
            }
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { deleteJobId = null }) { Text("Отмена") } },
    ) }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, title = { Text("Очистить журнал?") },
        text = { Text("Завершённые задания и их результаты будут удалены. Активное задание и скачанные файлы сохранятся.") },
        confirmButton = { TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                controller.clearHistory()
                val updated = controller.database.jobs()
                withContext(Dispatchers.Main) {
                    journal = updated
                    if (jobId != null && updated.none { it.id == jobId }) {
                        jobId = null; job = null; visibleImages = emptyList()
                    }
                    if (activeJobId != null && updated.none { it.id == activeJobId })
                        activeJobId = null
                    confirmClear = false
                }
            }
        }) { Text("Очистить") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Отмена") } },
    )
    BackHandler(enabled = view != "setup") {
        if (view == "results" && returnToChecks) {
            view = "checks"; returnToChecks = false; jobId = activeJobId; readOnly = false
        } else if (view == "results" && readOnly) view = "history"
        else {
            view = "setup"
            jobId = activeJobId
            job = null
            visibleImages = emptyList()
            readOnly = false
        }
    }
    if (confirmTokenDelete) AlertDialog(
        onDismissRequest = { confirmTokenDelete = false },
        title = { Text("Удалить ключ VK?") },
        text = { Text("Поиск новых изображений станет недоступен, пока ключ не будет сохранён снова.") },
        confirmButton = { TextButton(onClick = {
            scope.launch(Dispatchers.IO) {
                val deleted = controller.tokens.delete()
                withContext(Dispatchers.Main) {
                    tokenPresent = !deleted
                    tokenVerified = false
                    tokenError = false
                    prefs.edit().putBoolean("key_verified", false)
                        .putBoolean("key_error", false).apply()
                    confirmTokenDelete = false
                    if (!deleted) message = "Не удалось удалить ключ"
                }
            }
        }) { Text("Удалить") } },
        dismissButton = { TextButton(onClick = { confirmTokenDelete = false }) { Text("Отмена") } },
    )
    if (confirmTokenReplace) AlertDialog(
        onDismissRequest = { confirmTokenReplace = false },
        title = { Text("Заменить ключ VK?") },
        confirmButton = { TextButton(onClick = {
            confirmTokenReplace = false
            scope.launch(Dispatchers.IO) {
                val saved = controller.tokens.save(tokenDraft)
                withContext(Dispatchers.Main) {
                    if (saved) {
                        tokenDraft = ""; tokenPresent = true; tokenVerified = false; tokenError = false
                        prefs.edit().putBoolean("key_verified", false)
                            .putBoolean("key_error", false).apply()
                    } else message = "Ключ не сохранён: проверьте формат и хранилище Android"
                }
            }
        }) { Text("Заменить") } },
        dismissButton = { TextButton(onClick = { confirmTokenReplace = false }) { Text("Отмена") } },
    )

    if (view == "results") {
        VkCommunityResultsScreen(job, visibleImages, page, hasPendingSelection, readOnly,
            database = controller.database,
            onBack = {
                view = when { returnToChecks -> "checks"; readOnly -> "history"; else -> "setup" }
                if (returnToChecks || !readOnly) jobId = activeJobId
                returnToChecks = false
                readOnly = false
            },
            onPage = { page = it },
            onSelect = { item, checked ->
                visibleImages = visibleImages.map {
                    if (it.candidate.photoKey == item.candidate.photoKey) it.copy(selected = checked) else it
                }
                scope.launch(Dispatchers.IO) {
                    controller.database.select(item.jobId, item.candidate.photoKey, checked)
                }
            },
            onSelectPost = { item, checked ->
                visibleImages = visibleImages.map {
                    if (it.candidate.postKey == item.candidate.postKey &&
                        it.phase in setOf(VkItemPhase.FOUND, VkItemPhase.ERROR))
                        it.copy(selected = checked) else it
                }
                scope.launch(Dispatchers.IO) {
                    controller.database.selectPost(item.jobId, item.candidate.postKey, checked)
                }
            },
            onSelectAll = { checked -> jobId?.let { id -> scope.launch(Dispatchers.IO) {
                controller.database.selectAll(id, checked)
            } } },
            onDownload = { jobId?.let(controller::download) },
            onRetry = { jobId?.let { controller.download(it, true) } },
            onCancel = { jobId?.let(controller::cancel) },
            onContinue = { jobId?.let(controller::continueSearch) },
            onRepeat = { job?.let { old -> scope.launch(Dispatchers.IO) {
                val started = controller.repeat(old)
                withContext(Dispatchers.Main) {
                    if (started == null) { message = "Проверьте ключ VK"; view = "setup" }
                    else { activeJobId = started; jobId = started; job = null; page = 0; readOnly = false; view = "results" }
                }
            } } },
            onFinishReview = if (returnToChecks) ({ jobId?.let { id ->
                scope.launch(Dispatchers.IO) {
                    val done = controller.completeFavoriteReview(id)
                    withContext(Dispatchers.Main) {
                        if (done) {
                            favoriteBatch = controller.favoriteChecks.current()
                            view = "checks"; returnToChecks = false; jobId = activeJobId
                            readOnly = false
                        } else reviewError = "Проверьте результат поиска и дату сообщества. Изображения останутся для следующей проверки."
                    }
                }
            } }) else null,
            isFavoriteCheck = returnToChecks,
        )
        return
    }
    if (view == "checks") {
        VkFavoriteCheckScreen(favoriteBatch, favoriteJobs, favorites,
            onBack = { view = "setup"; jobId = activeJobId },
            onOpen = { entry ->
                returnToChecks = true
                jobId = entry.jobId; job = null; visibleImages = emptyList()
                page = 0; readOnly = entry.reviewed; view = "results"
            },
            onRetry = { scope.launch(Dispatchers.IO) { controller.retryFavoriteCheck() } },
            onNewCheck = { scope.launch(Dispatchers.IO) {
                val started = controller.checkFavoritePublications()
                withContext(Dispatchers.Main) {
                    if (started == null) { message = "Проверьте ключ VK и избранные сообщества"; view = "setup" }
                    else { favoriteBatch = started; favoriteJobs = emptyMap() }
                }
            } })
        return
    }
    if (view == "history") {
        VkCommunityHistoryScreen(journal, onBack = {
            view = "setup"; jobId = activeJobId; job = null; visibleImages = emptyList();
            readOnly = false
        },
            onOpen = { selected -> jobId = selected.id; job = selected; visibleImages = emptyList();
                page = 0; readOnly = true; view = "results" },
            onRepeat = { old -> scope.launch(Dispatchers.IO) {
                val started = controller.repeat(old)
                withContext(Dispatchers.Main) {
                    if (started == null) { message = "Проверьте ключ VK"; view = "setup" }
                    else { activeJobId = started; jobId = started; job = null; page = 0; readOnly = false; view = "results" }
                }
            } },
            onDelete = { deleteJobId = it.id }, onClear = { confirmClear = true })
        return
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).then(
        if (tab == 0) Modifier.verticalScroll(rememberScrollState()) else Modifier)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Назад") }
            Text("Загрузка из сообщества VK", style = MaterialTheme.typography.titleLarge)
        }
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Загрузка") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Как получить ключ") })
        }
        Spacer(Modifier.height(12.dp))
        if (tab == 1) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("1. Откройте официальный кабинет разработчика VK и создайте приложение.")
                    TextButton(onClick = { uriHandler.openUri("https://dev.vk.com/") }) {
                        Text("Открыть кабинет VK")
                    }
                    Text("2. В настройках созданного приложения найдите сервисный ключ доступа. Не используйте пароль от аккаунта или чужой ключ.")
                    Text("3. Вставьте ключ ниже и сохраните. Затем укажите открытое сообщество на вкладке «Загрузка» и выполните поиск — ответ VK проверит ключ и доступ к стене.")
                    Text("Ключ хранится на этом устройстве с шифрованием Android Keystore и не выводится в журнал. Некоторые стены и методы могут быть недоступны сервисному ключу.")
                }
                item {
                    OutlinedTextField(value = tokenDraft, onValueChange = { tokenDraft = it },
                        modifier = Modifier.fillMaxWidth(), label = { Text("Сервисный ключ VK") },
                        visualTransformation = PasswordVisualTransformation(), singleLine = true)
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(enabled = tokenDraft.isNotBlank(), onClick = {
                            if (tokenPresent) confirmTokenReplace = true else scope.launch(Dispatchers.IO) {
                                val saved = controller.tokens.save(tokenDraft)
                                withContext(Dispatchers.Main) {
                                    if (saved) {
                                        tokenDraft = ""; tokenPresent = true; tokenVerified = false
                                        tokenError = false
                                        prefs.edit().putBoolean("key_verified", false)
                                            .putBoolean("key_error", false).apply()
                                    } else message = "Ключ не сохранён: проверьте формат и хранилище Android"
                                }
                            }
                        }) { Text(if (tokenPresent) "Заменить" else "Сохранить") }
                        if (tokenPresent) TextButton(onClick = { confirmTokenDelete = true }) { Text("Удалить") }
                    }
                }
                item { Text("Проверка ключа происходит при поиске. Закрытые стены и методы без прав приложение не обходит.") }
            }
        } else {
            Card(colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Text("Сообщество", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(value = link, onValueChange = {
                        link = it
                        prefs.edit().putString("community_link", it).apply()
                    }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("Ссылка или адрес сообщества") },
                        placeholder = { Text("https://vk.com/club123") })
                    if (link.isNotBlank() && VkCommunityLink.parse(link) == null)
                        Text("Некорректный адрес сообщества VK",
                            color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(10.dp))
                    val keyOk = tokenPresent && tokenVerified && !tokenError
                    Surface(shape = RoundedCornerShape(10.dp),
                        color = if (keyOk) Color(0xFF174C32) else Color(0xFF5A2532)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (keyOk) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                                null, tint = if (keyOk) Color(0xFFACF0C2) else Color(0xFFFFBBC5))
                            Text(when {
                                keyOk -> "Ключ VK проверен"
                                tokenError -> "Ключ VK отклонён"
                                tokenPresent -> "Ключ VK сохранён · не проверен"
                                else -> "Ключ VK не добавлен"
                            }, Modifier.padding(start = 8.dp),
                                color = if (keyOk) Color(0xFFACF0C2) else Color(0xFFFFBBC5))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Период", style = MaterialTheme.typography.titleSmall)
                    Text("${period.start.format(DATE)} — ${period.endInclusive.format(DATE)}")
                    OutlinedButton(onClick = { showPeriod = true }) { Text("Изменить период") }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = VkCommunityLink.parse(link) != null, onClick = {
                    val requestedLink = link
                    val fallbackName = requestedLink.substringBefore('?').substringAfterLast('/')
                    favoriteLookupLink = requestedLink
                    favoriteName = fallbackName
                    favoriteAvatarUrl = null
                    favoriteLoading = true
                    showFavoriteDialog = true
                    scope.launch {
                        val info = withTimeoutOrNull(10_000L) {
                            withContext(Dispatchers.IO) {
                                val token = controller.tokens.read()
                                val parsed = VkCommunityLink.parse(requestedLink)
                                if (token == null || parsed == null) null else
                                    runCatching { communityApi.communityInfo(parsed, token) }.getOrNull()
                            }
                        }
                        if (showFavoriteDialog && favoriteLookupLink == requestedLink) {
                            if (info != null) {
                                if (favoriteName == fallbackName) favoriteName = info.name
                                favoriteAvatarUrl = info.avatarUrl
                            }
                            favoriteLoading = false
                        }
                    }
                }) { Text("☆ В избранное") }
                OutlinedButton(onClick = {
                    scope.launch(Dispatchers.IO) {
                        val records = controller.database.jobs()
                        withContext(Dispatchers.Main) { journal = records; view = "history" }
                    }
                }) { Text("Журнал") }
            }
            if (favorites.isNotEmpty()) {
                Card(colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text("Избранные сообщества", style = MaterialTheme.typography.titleSmall)
                        favorites.forEach { favorite ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = {
                                    link = favorite.link
                                    prefs.edit().putString("community_link", link).apply()
                                }, modifier = Modifier.weight(1f)) {
                                    Row(Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        favorite.avatarUrl?.let { avatar ->
                                            RemotePreview(avatar,
                                                Modifier.size(36.dp).clip(CircleShape), "vk")
                                        }
                                        Column(Modifier.padding(start =
                                            if (favorite.avatarUrl == null) 0.dp else 8.dp)) {
                                            Text(favorite.label, maxLines = 1,
                                                overflow = TextOverflow.Ellipsis)
                                            Text("С ${runCatching { LocalDate.parse(favorite.sinceDate).format(DATE) }.getOrDefault(favorite.sinceDate)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                                IconButton(onClick = { favoriteDateTarget = favorite }) {
                                    Icon(Icons.Rounded.DateRange,
                                        "Изменить дату для ${favorite.label}")
                                }
                                IconButton(onClick = { favoriteToRemove = favorite }) {
                                    Icon(Icons.Rounded.Close,
                                        "Удалить ${favorite.label} из избранного")
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(enabled = tokenPresent, onClick = {
                    val existing = controller.favoriteChecks.current()
                    val pending = existing?.entries?.any { entry ->
                        !entry.reviewed && favorites.firstOrNull { it.key == entry.favoriteKey }?.let {
                            it.sinceDate == entry.sinceDate &&
                                it.reviewedThroughEpoch == entry.reviewedThroughAtStart
                        } == true && controller.database.job(entry.jobId)?.let {
                            it.phase == VkJobPhase.SCANNING || it.scanComplete
                        } == true
                    } == true
                    if (pending) {
                        favoriteBatch = existing
                        view = "checks"
                    } else scope.launch(Dispatchers.IO) {
                        val started = controller.checkFavoritePublications()
                        withContext(Dispatchers.Main) {
                            if (started == null) message = "Проверьте ключ VK и избранные сообщества"
                            else { favoriteBatch = started; view = "checks" }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) {
                    Text("Проверить публикации")
                }
                if (favoriteBatch != null) TextButton(onClick = { view = "checks" }) {
                    Text("Последняя проверка")
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(enabled = tokenPresent && VkCommunityLink.parse(link) != null &&
                job?.phase !in setOf(VkJobPhase.SCANNING, VkJobPhase.DOWNLOADING),
                onClick = {
                    message = null
                    scope.launch(Dispatchers.IO) {
                        val started = controller.search(link, period)
                        withContext(Dispatchers.Main) {
                            if (started == null) message = "Проверьте адрес сообщества и ключ VK"
                            else { activeJobId = started; jobId = started; job = null; page = 0; readOnly = false; view = "results" }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text("Найти изображения") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            job?.let { current ->
                Spacer(Modifier.height(12.dp))
                Text(phaseText(current), style = MaterialTheme.typography.titleMedium)
                Text("Найдено ${current.found} · выбрано ${current.selected} · скачано ${current.downloaded}")
                OutlinedButton(onClick = { view = "results" }) {
                    Text("Открыть результаты")
                }
            }

        }
    }
}

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru"))
private fun phaseText(job: VkCommunityJob): String = when (job.phase) {
    VkJobPhase.SCANNING -> "Идёт поиск"
    VkJobPhase.READY -> "Поиск завершён"
    VkJobPhase.DOWNLOADING -> "Идёт загрузка"
    VkJobPhase.FINISHED -> "Загрузка завершена"
    VkJobPhase.ERROR -> "Ошибка"
    VkJobPhase.CANCELLED -> "Задание остановлено"
}
