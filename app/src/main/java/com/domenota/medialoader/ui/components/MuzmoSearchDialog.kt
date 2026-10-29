package com.domenota.medialoader.ui.components

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.MusicHttpFailurePolicy
import com.domenota.medialoader.core.logging.MusicHttpLogDecision
import com.domenota.medialoader.core.logging.SafeWebConsole
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.download.MuzmoDownloadUrls
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.json.JSONTokener

private data class MuzmoRow(val artist: String, val title: String, val infoUrl: String, val duration: String?)

/** Isolated browser: does not alter global VPN/proxy or other media providers. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MuzmoSearchDialog(
    initialArtist: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    recognitionMessage: String? = null,
    recognizedArtworkUrl: String? = null,
    onOtherSource: ((artist: String, title: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val repo = remember(context) { MediaRepository.get(context) }
    val previewPlayer = rememberMusicPreviewPlayer()
    val downloads by repo.downloads.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val operationId = remember { AppLog.newOperationId("muzmo") }
    val snapshot = remember { initialArtist to initialTitle }
    val launchArtwork = remember { recognizedArtworkUrl }
    var artist by rememberSaveable { mutableStateOf(snapshot.first) }
    var title by rememberSaveable { mutableStateOf(snapshot.second) }
    var results by remember { mutableStateOf<List<MuzmoRow>>(emptyList()) }
    var queryUrl by rememberSaveable {
        mutableStateOf((snapshot.first + " " + snapshot.second).trim()
            .takeIf(String::isNotBlank)?.let(MuzmoDownloadUrls::searchUrl).orEmpty())
    }
    var searching by remember { mutableStateOf(queryUrl.isNotBlank()) }
    var pending by remember { mutableStateOf<MuzmoRow?>(null) }
    var pendingPreview by remember { mutableStateOf(false) }
    var previewLinks by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var problem by remember { mutableStateOf<String?>(null) }
    var searchId by remember { mutableIntStateOf(0) }
    var detailId by remember { mutableIntStateOf(0) }

    fun matchesQuery(address: String): Boolean = runCatching {
        MuzmoDownloadUrls.isSearchPage(address) &&
            Uri.parse(queryUrl).getQueryParameter("q")?.trim()
                .equals(Uri.parse(address).getQueryParameter("q")?.trim(), ignoreCase = true) &&
            !Uri.parse(queryUrl).getQueryParameter("q").isNullOrBlank()
    }.getOrDefault(false)

    val browser = remember(context) {
        WebView(context).apply {
            AppLog.verbose("Muzmo", "WebView created · op=$operationId · searchId=$searchId")
            settings.javaScriptEnabled = true
            settings.userAgentString = settings.userAgentString
                .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    AppLog.verbose("MuzmoWebView", SafeWebConsole.describe(message))
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val destination = request.url.toString()
                    AppLog.verbose("Muzmo", "Navigation · op=$operationId · mainFrame=${request.isForMainFrame} · method=${request.method} · redirect=${request.isRedirect} · allowed=${MuzmoDownloadUrls.isSitePage(destination)}")
                    return request.isForMainFrame && !MuzmoDownloadUrls.isSitePage(destination)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    AppLog.verbose("Muzmo", "Page started · op=$operationId · searchId=$searchId · detailId=$detailId · host=${Uri.parse(url.orEmpty()).host.orEmpty()}")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val address = url.orEmpty()
                    AppLog.verbose("Muzmo", "Page finished · op=$operationId · searchId=$searchId · detailId=$detailId · searchMatch=${matchesQuery(address)}")
                    if (searching && matchesQuery(address)) {
                        val id = searchId
                        var settledEmptyScans = 0
                        fun scan() {
                            if (!searching || id != searchId || view.url != address) return
                            view.evaluateJavascript(MuzmoPageScripts.SEARCH) { raw ->
                                if (!searching || id != searchId || view.url != address) return@evaluateJavascript
                                val data = decodeMuzmoJs(raw)
                                if (data == null) {
                                    searching = false
                                    problem = "Не удалось получить результаты поиска. Попробуйте ещё раз."
                                    AppLog.w("Muzmo", "DOM probe returned unreadable JSON · op=$operationId · searchId=$id")
                                    return@evaluateJavascript
                                }
                                val list = parseMuzmoRows(data)
                                if (list.isNotEmpty()) {
                                    results = list
                                    searching = false
                                    problem = null
                                    AppLog.i("Muzmo", "DOM search rows parsed · op=$operationId · count=${list.size} · durationKnown=${list.count { it.duration != null }}")
                                    if (list.any { it.duration == null }) {
                                        if (AppLog.isExtended()) {
                                            view.evaluateJavascript(MuzmoPageScripts.SEARCH_DIAGNOSTIC) { diagnosticRaw ->
                                                if (searchId == id && view.url == address) {
                                                    val diagnostic = decodeMuzmoJs(diagnosticRaw)
                                                    AppLog.verbose("Muzmo", "Search duration structure · op=$operationId · data=${diagnostic?.toString()?.take(2_400) ?: "unreadable"}")
                                                }
                                            }
                                        }
                                        fun refreshDurations(attempt: Int) {
                                            if (attempt > 2 || searchId != id || view.url != address ||
                                                searching || pending != null) return
                                            view.evaluateJavascript(MuzmoPageScripts.SEARCH) { refreshedRaw ->
                                                if (searchId != id || view.url != address || pending != null) return@evaluateJavascript
                                                val latest = parseMuzmoRows(decodeMuzmoJs(refreshedRaw))
                                                    .associateBy(MuzmoRow::infoUrl)
                                                var updated = 0
                                                results = results.map { current ->
                                                    val duration = latest[current.infoUrl]?.duration
                                                    if (current.duration == null && duration != null) {
                                                        updated++
                                                        current.copy(duration = duration)
                                                    } else current
                                                }
                                                if (updated > 0) AppLog.verbose("Muzmo",
                                                    "Late durations parsed · op=$operationId · updated=$updated · attempt=$attempt")
                                                if (results.any { it.duration == null }) {
                                                    view.postDelayed({ refreshDurations(attempt + 1) }, 1_200L)
                                                }
                                            }
                                        }
                                        view.postDelayed({ refreshDurations(1) }, 1_200L)
                                    }
                                } else if (data.optBoolean("explicitEmpty", false)) {
                                    searching = false
                                    problem = "По вашему запросу ничего не найдено."
                                    AppLog.i("Muzmo", "Search returned explicit empty state · op=$operationId · searchId=$id")
                                } else {
                                    val links = data.optInt("links", 0)
                                    val ready = data.optString("readyState")
                                    settledEmptyScans = if (ready == "complete") settledEmptyScans + 1 else 0
                                    if (MuzmoDomProbePolicy.exhausted(ready, settledEmptyScans, links > 0)) {
                                        searching = false
                                        problem = "Не удалось получить результаты поиска. Попробуйте ещё раз."
                                        AppLog.w("Muzmo", "Settled search DOM has no usable rows · op=$operationId · searchId=$id · links=$links · scans=$settledEmptyScans")
                                    } else {
                                        AppLog.verboseSampled("Muzmo", "search-awaiting:$id",
                                            "Search DOM awaiting rows · links=$links · ready=$ready · settledScans=$settledEmptyScans")
                                        view.postDelayed({ scan() }, 900L)
                                    }
                                }
                            }
                        }
                        scan()
                    } else {
                        val selection = pending ?: return
                        if (address != selection.infoUrl) return
                        val id = detailId
                        var settledDetailScans = 0
                        fun inspect() {
                            if (pending != selection || detailId != id || view.url != address) return
                            view.evaluateJavascript(MuzmoPageScripts.DETAIL) { raw ->
                                if (pending != selection || detailId != id || view.url != address) return@evaluateJavascript
                                val document = decodeMuzmoJs(raw)
                                if (document == null) {
                                    pending = null
                                    problem = "Не удалось прочитать страницу композиции."
                                    AppLog.w("Muzmo", "Info page DOM probe returned unreadable JSON · op=$operationId · detailId=$id")
                                    return@evaluateJavascript
                                }
                                val link = document.optString("downloadUrl")
                                if (MuzmoDownloadUrls.isMp3(link)) {
                                    val previewOnly = pendingPreview
                                    pending = null
                                    pendingPreview = false
                                    if (previewOnly) {
                                        previewLinks = previewLinks + (selection.infoUrl to link)
                                        previewPlayer.toggle(link)
                                    } else scope.launch {
                                        try {
                                            val artwork = launchArtwork?.takeIf {
                                                isSafeBrowserUrl(it) &&
                                                    matchesRecognizedMusic(snapshot.first, snapshot.second, artist, title) &&
                                                    matchesRecognizedMusic(snapshot.first, snapshot.second,
                                                        selection.artist, selection.title)
                                            }
                                            val queued = repo.enqueueMuzmoMp3(link, selection.artist, selection.title,
                                                sourcePageUrl = selection.infoUrl, artworkUrl = artwork)
                                            Toast.makeText(context, "Добавлено в загрузки", Toast.LENGTH_SHORT).show()
                                            AppLog.i("Muzmo", "Selected MP3 queued · op=$operationId · downloadId=${queued.id}")
                                        } catch (e: Exception) {
                                            problem = e.message ?: "Не удалось начать загрузку."
                                            AppLog.w("Muzmo", "MP3 enqueue failed · op=$operationId", e)
                                        }
                                    }
                                } else {
                                    val ready = document.optString("readyState")
                                    settledDetailScans = if (ready == "complete") settledDetailScans + 1 else 0
                                    if (MuzmoDomProbePolicy.exhausted(ready, settledDetailScans, false)) {
                                        pending = null
                                        problem = "На странице нет подтверждённой ссылки MP3. Повторите попытку."
                                        AppLog.w("Muzmo", "Settled info DOM has no trusted MP3 link · op=$operationId · detailId=$id · scans=$settledDetailScans")
                                    } else {
                                        AppLog.verboseSampled("Muzmo", "detail-awaiting:$id",
                                            "Info page awaits trusted MP3 link · ready=$ready · settledScans=$settledDetailScans")
                                        view.postDelayed({ inspect() }, 900L)
                                    }
                                }
                            }
                        }
                        inspect()
                    }
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    val code = error.errorCode
                    // Only a failed user-initiated action is a terminal ERROR.
                    when {
                        searching -> AppLog.e("Muzmo",
                            "Search document network failure · op=$operationId · code=$code")
                        pending != null -> AppLog.e("Muzmo",
                            "Info page network failure · op=$operationId · code=$code")
                        else -> AppLog.w("Muzmo",
                            "Main-frame network error · op=$operationId · code=$code")
                    }
                    if (searching) {
                        searching = false
                        problem = "Не удалось подключиться к музыкальному источнику."
                    } else if (pending != null) {
                        pending = null
                        problem = "Не удалось открыть страницу композиции."
                    }
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: android.webkit.WebResourceResponse) {
                    val status = errorResponse.statusCode
                    val decision = MusicHttpFailurePolicy.classify(
                        statusCode = status,
                        isMainFrame = request.isForMainFrame,
                        searching = searching,
                        detailPending = pending != null,
                    )
                    if (decision != MusicHttpLogDecision.IGNORED) {
                        // Historical WebView detail remains DEBUG. A failed user action has
                        // its own ERROR event even when a VPN/IP is responsible for HTTP 403.
                        AppLog.verbose("MuzmoWebView",
                            "HTTP error · mainFrame=${request.isForMainFrame} · method=${request.method} · status=$status · mime=${errorResponse.mimeType.orEmpty().take(40)} · host=${request.url.host.orEmpty()}")
                        val event = "${MusicHttpFailurePolicy.eventName(decision)} · op=$operationId · status=$status"
                        when (decision) {
                            MusicHttpLogDecision.SEARCH_FAILED,
                            MusicHttpLogDecision.DETAIL_FAILED -> AppLog.e("Muzmo", event)
                            MusicHttpLogDecision.MAIN_FRAME_WARNING -> AppLog.w("Muzmo", event)
                            else -> Unit // subresource failures are not terminal
                        }
                    }
                    if (request.isForMainFrame && status >= 400) {
                        searching = false
                        pending = null
                        problem = "Музыкальный источник временно недоступен."
                    }
                }
            }
        }
    }
    DisposableEffect(browser) {
        onDispose {
            AppLog.verbose("Muzmo", "WebView destroyed · op=$operationId · searchId=$searchId · detailId=$detailId")
            browser.stopLoading()
            browser.webViewClient = WebViewClient()
            browser.webChromeClient = WebChromeClient()
            (browser.parent as? ViewGroup)?.removeView(browser)
            browser.destroy()
        }
    }
    LaunchedEffect(browser) {
        if (queryUrl.isNotBlank()) browser.loadUrl(queryUrl)
    }
    fun reset() {
        previewPlayer.stop()
        previewLinks = emptyMap()
        searchId++
        detailId++
        searching = false
        pending = null
        results = emptyList()
        queryUrl = ""
        problem = null
        browser.stopLoading()
    }
    fun search() {
        val query = listOf(artist.trim(), title.trim()).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return
        reset()
        queryUrl = MuzmoDownloadUrls.searchUrl(query)
        searching = true
        AppLog.i("Muzmo", "Full-song search requested · op=$operationId · queryChars=${query.length}")
        browser.loadUrl(queryUrl)
    }
    fun select(row: MuzmoRow, previewOnly: Boolean = false) {
        if (searching || pending != null || results.none { it.infoUrl == row.infoUrl }) return
        if (previewOnly) previewLinks[row.infoUrl]?.let { link ->
            previewPlayer.toggle(link)
            return
        }
        detailId++
        pending = row
        pendingPreview = previewOnly
        problem = null
        AppLog.i("Muzmo", "Track selected · op=$operationId · detailId=$detailId")
        browser.loadUrl(row.infoUrl)
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        Surface(modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.9f),
            shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            Box {
                Column(Modifier.fillMaxSize().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Поиск музыки", style = MaterialTheme.typography.titleMedium)
                            Text(recognitionMessage ?: "Резервный источник",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (recognitionMessage?.startsWith("Трек распознан") == true)
                                    MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onDismiss) { Text("Закрыть") }
                    }
                    OutlinedTextField(artist, { if (artist != it) { artist = it; reset() } },
                        label = { Text("Исполнитель") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(title, { if (title != it) { title = it; reset() } },
                        label = { Text("Название") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = ::search,
                            enabled = artist.isNotBlank() || title.isNotBlank()) {
                            Text("Найти")
                        }
                        if (onOtherSource != null) TextButton(
                            onClick = { onOtherSource(artist.trim(), title.trim()) },
                        ) { Text("Другой источник") }
                    }
                    HorizontalDivider()
                    if (searching || pending != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    previewPlayer.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (pending != null) Text("Получаем оригинальный MP3…")
                    if (results.isEmpty()) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            if (problem == null) {
                                Text(if (searching) "Ищем подходящие версии…" else
                                    if (queryUrl.isBlank()) "Введите исполнителя или название."
                                    else "По вашему запросу ничего не найдено.")
                            }
                        }
                    } else {
                        LazyColumn(modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(results, key = { it.infoUrl }) { row ->
                                val task = downloads.filter {
                                    it.providerId == "muzmo" && it.sourcePageUrl == row.infoUrl
                                }.maxByOrNull { it.createdAtEpochMillis }
                                Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                                    Row(Modifier.fillMaxWidth().padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.MusicNote, contentDescription = null)
                                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                            Text(row.title, style = MaterialTheme.typography.bodyLarge)
                                            Text(row.artist, style = MaterialTheme.typography.bodySmall)
                                            row.duration?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                                            if (previewPlayer.currentUrl == previewLinks[row.infoUrl]) {
                                                MusicPreviewTimeline(previewPlayer)
                                            }
                                        }
                                        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                                            if (previewPlayer.currentUrl == previewLinks[row.infoUrl] &&
                                                previewPlayer.buffering) CircularProgressIndicator(Modifier.size(24.dp))
                                            else IconButton(
                                                enabled = !searching && pending == null,
                                                onClick = { select(row, previewOnly = true) },
                                            ) {
                                                Icon(
                                                    if (previewPlayer.currentUrl == previewLinks[row.infoUrl] &&
                                                        previewPlayer.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                                    contentDescription = if (previewPlayer.currentUrl == previewLinks[row.infoUrl] &&
                                                        previewPlayer.playing) "Пауза" else "Прослушать",
                                                )
                                            }
                                        }
                                        when (task?.state) {
                                            DownloadState.COMPLETED ->
                                                Icon(Icons.Rounded.CheckCircle, contentDescription = "Скачано",
                                                    tint = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
                                                        Color(0xFF15964A) else Color(0xFF65D49A))
                                            DownloadState.QUEUED, DownloadState.RUNNING ->
                                                CircularProgressIndicator(Modifier.size(24.dp))
                                            DownloadState.FAILED, DownloadState.CANCELLED ->
                                                IconButton(enabled = !searching && pending == null,
                                                    onClick = { select(row) }) {
                                                    Icon(Icons.Rounded.ErrorOutline,
                                                        contentDescription = "Повторить загрузку",
                                                        tint = MaterialTheme.colorScheme.error)
                                                }
                                            else -> IconButton(
                                                enabled = !searching && pending == null,
                                                onClick = { select(row) }
                                            ) { Icon(Icons.Rounded.Download, contentDescription = "Скачать") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                AndroidView(factory = { browser },
                    modifier = Modifier.size(1.dp).alpha(0f).align(Alignment.BottomEnd))
            }
        }
    }
}

private fun decodeMuzmoJs(raw: String?): JSONObject? = runCatching {
    val inner = JSONTokener(raw.orEmpty()).nextValue() as? String
    inner?.let(::JSONObject)
}.getOrNull()

private fun parseMuzmoRows(data: JSONObject?): List<MuzmoRow> {
    val items = data?.optJSONArray("tracks") ?: return emptyList()
    return buildList {
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val info = item.optString("infoUrl")
            if (!MuzmoDownloadUrls.isInfoPage(info)) continue
            val artist = item.optString("artist").trim()
            val title = item.optString("title").trim()
            if (artist.isNotBlank() && title.isNotBlank()) {
                add(MuzmoRow(artist, title, info, item.optString("duration").ifBlank { null }))
            }
        }
    }.distinctBy(MuzmoRow::infoUrl)
}
