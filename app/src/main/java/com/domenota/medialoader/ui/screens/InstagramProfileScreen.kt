package com.domenota.medialoader.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralOutlinedButton as OutlinedButton
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.domenota.medialoader.data.instagramprofile.InstagramFavorite
import com.domenota.medialoader.data.instagramprofile.InstagramProfileController
import com.domenota.medialoader.data.instagramprofile.InstagramProfileInfo
import com.domenota.medialoader.data.instagramprofile.InstagramProfileJob
import com.domenota.medialoader.data.instagramprofile.InstagramProfileLink
import com.domenota.medialoader.data.instagramprofile.InstagramProfilePhase
import com.domenota.medialoader.data.instagramprofile.InstagramStoredPost
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.InstagramApiClient
import com.domenota.medialoader.core.provider.InstagramProvider
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.vkcommunity.VkDatePeriod
import com.domenota.medialoader.ui.components.RemotePreview
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun InstagramProfileScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { InstagramProfileController(context.applicationContext) }
    var jobs by remember { mutableStateOf(controller.store.jobs()) }
    var favorites by remember { mutableStateOf(controller.favorites.list()) }
    var activeId by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf("search") }
    var input by rememberSaveable { mutableStateOf("") }
    var withReels by rememberSaveable { mutableStateOf(false) }
    var period by remember { mutableStateOf<VkDatePeriod?>(null) }
    var showCalendar by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var removeFavorite by remember { mutableStateOf<InstagramFavorite?>(null) }
    var removeJob by remember { mutableStateOf<InstagramProfileJob?>(null) }
    var favoriteDateJob by remember { mutableStateOf<InstagramProfileJob?>(null) }
    val activeJob = jobs.firstOrNull { it.id == activeId }
    var posts by remember(activeId) { mutableStateOf(activeId?.let(controller.store::posts).orEmpty()) }

    LaunchedEffect(activeId) {
        while (true) {
            jobs = controller.store.jobs()
            favorites = controller.favorites.list()
            posts = activeId?.let(controller.store::posts).orEmpty()
            delay(800)
        }
    }

    if (showCalendar) VkPeriodPicker(
        current = period ?: VkDatePeriod.lastDays(30, LocalDate.now()),
        onApply = { period = it; showCalendar = false },
        onDismiss = { showCalendar = false },
    )
    favoriteDateJob?.let { target ->
        VkPeriodPicker(
            current = VkDatePeriod.of(LocalDate.now().minusDays(30), LocalDate.now()),
            singleDate = true,
            onApply = { selected ->
                val info = InstagramProfileInfo(target.userId.orEmpty(), target.username,
                    target.profileName ?: target.username, target.avatarUrl, false)
                controller.favorites.save(info, selected.start, target.includeReels)
                favorites = controller.favorites.list()
                favoriteDateJob = null
            },
            onDismiss = { favoriteDateJob = null },
        )
    }
    removeFavorite?.let { favorite ->
        AlertDialog(onDismissRequest = { removeFavorite = null },
            title = { Text("Удалить из избранного?") },
            text = { Text(favorite.label) },
            confirmButton = { TextButton(onClick = {
                controller.favorites.remove(favorite.username)
                favorites = controller.favorites.list()
                removeFavorite = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { removeFavorite = null }) { Text("Отмена") } })
    }
    removeJob?.let { job ->
        AlertDialog(onDismissRequest = { removeJob = null },
            title = { Text("Удалить запись журнала?") },
            text = { Text("Скачанные файлы останутся на устройстве.") },
            confirmButton = { TextButton(onClick = {
                controller.store.delete(job.id)
                jobs = controller.store.jobs()
                removeJob = null
            }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { removeJob = null }) { Text("Отмена") } })
    }

    when (page) {
        "results" -> InstagramProfileResults(activeJob, posts,
            onBack = { page = "search" },
            onSelect = { code, selected ->
                activeId?.let { controller.store.select(it, code, selected) }
                posts = activeId?.let(controller.store::posts).orEmpty()
            },
            onSelectAll = { selected ->
                activeId?.let { controller.store.selectAll(it, selected) }
                posts = activeId?.let(controller.store::posts).orEmpty()
            },
            onContinue = { activeId?.let(controller::continueSearch) },
            onCancel = { activeId?.let(controller::cancel) },
            onDownload = { activeId?.let(controller::download) },
            onReview = { activeId?.let(controller::finishReview) },
            onVisualSelect = { code, visualId, selected, allIds ->
                activeId?.let { controller.store.selectVisual(it, code, visualId, selected, allIds) }
                posts = activeId?.let(controller.store::posts).orEmpty()
            },
        )
        "journal", "checks" -> Column(Modifier.fillMaxSize().padding(16.dp)) {
            Header(if (page == "checks") "Новые публикации" else "Журнал Instagram") { page = "search" }
            val visible = jobs.filter { if (page == "checks") it.favoriteCheck else true }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(visible, key = { it.id }) { job ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text(job.profileName ?: job.username, style = MaterialTheme.typography.titleMedium)
                            Text("${job.scanned} просмотрено · ${controller.store.posts(job.id).size} найдено · ${phaseLabel(job)}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${job.startDate ?: "Последние 300"} · ${if (job.includeReels) "с Reels" else "лента"}",
                                style = MaterialTheme.typography.bodySmall)
                            Row {
                                TextButton(onClick = { activeId = job.id; page = "results" }) { Text("Открыть") }
                                if (!job.favoriteCheck) TextButton(onClick = {
                                    activeId = controller.repeat(job)
                                    page = "results"
                                }) { Text("Повторить") }
                                if (job.phase !in setOf(InstagramProfilePhase.SCANNING,
                                        InstagramProfilePhase.DOWNLOADING))
                                    IconButton(onClick = { removeJob = job }) {
                                        Icon(Icons.Rounded.Close, "Удалить запись")
                                    }
                            }
                        }
                    }
                }
            }
            if (page == "checks") Button(onClick = {
                val started = controller.checkFavorites()
                if (started.isEmpty()) message = if (favorites.isEmpty()) "Добавьте профиль в избранное"
                    else "Сначала завершите просмотр предыдущей проверки"
                else { jobs = controller.store.jobs(); activeId = started.first() }
            }, enabled = jobs.none { it.favoriteCheck &&
                (it.phase == InstagramProfilePhase.SCANNING || it.scanComplete && !it.reviewed) },
                modifier = Modifier.fillMaxWidth()) { Text("Проверить публикации") }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Header("Загрузка из профиля Instagram", onBack)
            Text("Открытые профили · фото и видео", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(input, { input = it }, label = { Text("Ссылка или @имя профиля") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        isError = input.isNotBlank() && InstagramProfileLink.parse(input) == null)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = withReels, onCheckedChange = { withReels = it })
                        Text("Проверять вкладку Reels", modifier = Modifier.padding(start = 12.dp))
                    }
                    Text("Reels из основной ленты появятся и без этого переключателя.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("Период: ${period?.let { "${it.start} — ${it.endInclusive}" } ?: "последние 300 публикаций"}")
                    Row {
                        OutlinedButton(onClick = { showCalendar = true }) { Text("Выбрать даты") }
                        if (period != null) TextButton(onClick = { period = null }) { Text("Сбросить") }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                activeId = controller.search(input, period?.start, period?.endInclusive, withReels)
                if (activeId == null) message = "Введите ссылку на профиль Instagram"
                else { jobs = controller.store.jobs(); page = "results" }
            }, enabled = InstagramProfileLink.parse(input) != null,
                modifier = Modifier.fillMaxWidth()) { Text("Найти фото и видео") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row {
                OutlinedButton(onClick = { page = "journal" }) { Text("Журнал") }
                Spacer(Modifier.size(8.dp))
                OutlinedButton(onClick = { page = "checks" }) { Text("Проверить избранное") }
            }
            if (favorites.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text("Избранные профили", style = MaterialTheme.typography.titleMedium)
                favorites.forEach { favorite ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            RemotePreview(favorite.avatarUrl, Modifier.size(44.dp), "instagram")
                            Text(favorite.label,
                                Modifier.weight(1f).clickable {
                                    input = InstagramProfileLink(favorite.username).url
                                    withReels = favorite.includeReels
                                    period = VkDatePeriod.of(LocalDate.parse(favorite.sinceDate),
                                        LocalDate.now())
                                }.padding(8.dp), maxLines = 1)
                            IconButton(onClick = { removeFavorite = favorite }) {
                                Icon(Icons.Rounded.Close, "Удалить из избранного")
                            }
                        }
                    }
                }
            }
            activeJob?.takeIf { it.userId != null && it.username == InstagramProfileLink.parse(input)?.username }
                ?.let { job ->
                    TextButton(onClick = { favoriteDateJob = job }) { Text("☆ В избранное") }
                }
        }
    }
}

@Composable
private fun InstagramProfileResults(job: InstagramProfileJob?, posts: List<InstagramStoredPost>,
    onBack: () -> Unit, onSelect: (String, Boolean) -> Unit, onSelectAll: (Boolean) -> Unit,
    onContinue: () -> Unit, onCancel: () -> Unit, onDownload: () -> Unit,
    onReview: () -> Unit, onVisualSelect: (String, String, Boolean, List<String>) -> Unit) {
    var grid by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf<InstagramStoredPost?>(null) }
    preview?.let { selected ->
        InstagramProfilePreview(selected, onDismiss = { preview = null },
            onSelect = { visualId, checked, allIds ->
                onVisualSelect(selected.post.code, visualId, checked, allIds)
            })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header("Результаты Instagram", onBack)
        if (job == null) { Text("Задание не найдено"); return@Column }
        Text(job.profileName ?: job.username, style = MaterialTheme.typography.titleMedium)
        Text("${phaseLabel(job)} · просмотрено ${job.scanned} · найдено ${posts.size} · выбрано ${posts.count { it.selected }}")
        job.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (job.phase == InstagramProfilePhase.SCANNING || job.phase == InstagramProfilePhase.DOWNLOADING)
            CircularProgressIndicator(Modifier.padding(8.dp))
        Row {
            OutlinedButton(onClick = { onSelectAll(true) }) { Text("Выбрать всё") }
            Spacer(Modifier.size(4.dp))
            OutlinedButton(onClick = { onSelectAll(false) }) { Text("Снять выбор") }
        }
        Row {
            TextButton(onClick = { grid = !grid }) { Text(if (grid) "Список" else "Сетка") }
            if (job.phase in setOf(InstagramProfilePhase.SCANNING, InstagramProfilePhase.DOWNLOADING))
                TextButton(onClick = onCancel) { Text("Остановить") }
            if (!job.scanComplete && job.phase != InstagramProfilePhase.SCANNING)
                TextButton(onClick = onContinue) { Text("Продолжить") }
        }
        if (grid) LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(posts, key = { it.post.code }) { post -> InstagramPostTile(post,
                Modifier.fillMaxWidth().aspectRatio(1f),
                onToggle = { onSelect(post.post.code, !post.selected) },
                onPreview = { preview = post }) }
        } else LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(posts, key = { it.post.code }) { post ->
                Card(Modifier.fillMaxWidth().clickable { preview = post }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InstagramPostTile(post, Modifier.size(108.dp),
                            onToggle = { onSelect(post.post.code, !post.selected) },
                            onPreview = { preview = post })
                        Column(Modifier.padding(8.dp)) {
                            Text(if (post.post.video) "Видео" else "Публикация")
                            Text("${post.post.mediaCount} файл(ов) · ${post.post.epochSecond.asDate()}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (post.taskIds.isNotEmpty()) Text("В истории загрузок",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (job.favoriteCheck && job.scanComplete && !job.reviewed) OutlinedButton(onClick = onReview,
            modifier = Modifier.fillMaxWidth()) { Text("Завершить просмотр") }
        Button(onClick = onDownload, enabled = posts.any { it.selected } &&
            job.phase !in setOf(InstagramProfilePhase.SCANNING, InstagramProfilePhase.DOWNLOADING),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Скачать выбранное") }
    }
}

@Composable
private fun InstagramProfilePreview(post: InstagramStoredPost, onDismiss: () -> Unit,
    onSelect: (String, Boolean, List<String>) -> Unit) {
    val context = LocalContext.current
    var visuals by remember(post.post.code) { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember(post.post.code) { mutableStateOf(true) }
    var error by remember(post.post.code) { mutableStateOf<String?>(null) }
    var excluded by remember(post.post.code) { mutableStateOf(post.excludedIds) }
    LaunchedEffect(post.post.code) {
        try {
            val session = MediaRepository.get(context).sessions.cookies()
                ?: throw IllegalStateException("Войдите в Instagram")
            visuals = InstagramProvider.parseApiResponse(
                InstagramApiClient().mediaInfo(InstagramProvider.mediaId(post.post.code), session),
                post.post.code).filter { it.type != MediaType.AUDIO }
            if (!post.selected) excluded = visuals.map { it.id }.toSet()
        } catch (_: Exception) {
            error = "Не удалось открыть файлы публикации. Попробуйте позже."
        } finally { loading = false }
    }
    val pager = rememberPagerState(pageCount = { visuals.size.coerceAtLeast(1) })
    Dialog(onDismissRequest = onDismiss) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(12.dp)) {
                Text("Публикация · ${if (visuals.isEmpty()) post.post.mediaCount else visuals.size} файл(ов)",
                    style = MaterialTheme.typography.titleMedium)
                if (loading) Box(Modifier.fillMaxWidth().height(320.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                } else if (visuals.isNotEmpty()) {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxWidth().height(380.dp)) { index ->
                        val media = visuals[index]
                        Box(Modifier.fillMaxSize()) {
                            RemotePreview(media.previewUrl ?: if (media.type == MediaType.PHOTO)
                                media.downloadUrl else post.post.previewUrl,
                                Modifier.fillMaxSize(), "instagram")
                            Surface(Modifier.align(Alignment.TopStart).padding(8.dp)
                                .clickable {
                                    val checked = media.id in excluded
                                    excluded = if (checked) excluded - media.id else excluded + media.id
                                    onSelect(media.id, checked, visuals.map { it.id })
                                }, shape = CircleShape,
                                color = if (media.id !in excluded) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                Text(if (media.id !in excluded) "✓" else "+", Modifier.padding(10.dp),
                                    color = if (media.id !in excluded) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurface)
                            }
                            if (media.type == MediaType.VIDEO) Text("Видео",
                                Modifier.align(Alignment.BottomStart).padding(8.dp),
                                color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    Text("${pager.currentPage + 1} из ${visuals.size} · листайте изображения",
                        style = MaterialTheme.typography.bodySmall)
                } else Text(error ?: "Файлы не найдены", Modifier.padding(vertical = 20.dp))
                Row {
                    TextButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(post.post.pageUrl)))
                    }) { Text("Открыть в Instagram") }
                    TextButton(onClick = onDismiss) { Text("Закрыть") }
                }
            }
        }
    }
}

@Composable
private fun InstagramPostTile(post: InstagramStoredPost, modifier: Modifier = Modifier.size(112.dp),
    onToggle: () -> Unit, onPreview: () -> Unit) {
    Box(modifier.clickable(onClick = onPreview)) {
        RemotePreview(post.post.previewUrl, Modifier.fillMaxSize(), "instagram")
        Surface(Modifier.align(Alignment.TopStart).padding(4.dp).size(28.dp)
            .clickable(onClick = onToggle), shape = CircleShape,
            color = if (post.selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
            if (post.selected) Icon(Icons.Rounded.Check, "Выбрано", Modifier.padding(3.dp),
                tint = MaterialTheme.colorScheme.onPrimary)
        }
        if (post.post.video) Icon(Icons.Rounded.PlayArrow, "Видео",
            Modifier.align(Alignment.TopEnd).padding(4.dp), tint = MaterialTheme.colorScheme.onSurface)
        if (post.post.mediaCount > 1) Surface(Modifier.align(Alignment.BottomEnd).padding(4.dp),
            shape = RoundedCornerShape(5.dp), color = MaterialTheme.colorScheme.surface) {
            Text(post.post.mediaCount.toString(), Modifier.padding(horizontal = 5.dp))
        }
    }
}

@Composable private fun Header(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Rounded.ArrowBack, "Назад") }
        Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 2,
            overflow = TextOverflow.Ellipsis)
    }
}

private fun phaseLabel(job: InstagramProfileJob) = when (job.phase) {
    InstagramProfilePhase.SCANNING -> "Идёт поиск"
    InstagramProfilePhase.READY -> if (job.scanComplete) "Поиск завершён" else "Пауза после ${job.batchLimit} публикаций"
    InstagramProfilePhase.DOWNLOADING -> "Добавление в загрузки"
    InstagramProfilePhase.FINISHED -> "Добавлено в загрузки"
    InstagramProfilePhase.ERROR -> "Проверка остановлена"
    InstagramProfilePhase.CANCELLED -> "Задание остановлено"
}

private fun Long.asDate(): String = if (this <= 0) "Дата неизвестна" else
    Instant.ofEpochSecond(this).atZone(ZoneId.systemDefault()).toLocalDate()
        .format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale("ru")))
