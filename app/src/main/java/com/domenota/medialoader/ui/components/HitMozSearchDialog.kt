package com.domenota.medialoader.ui.components

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import com.domenota.medialoader.ui.components.NeutralTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.SafeWebConsole
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.download.HitMozDownloadUrls
import com.domenota.medialoader.data.search.HitMozSearchClient
import com.domenota.medialoader.data.search.MusicArtworkLookup
import com.domenota.medialoader.data.search.HitMozTrack
import com.domenota.medialoader.data.search.MusicAdFilter
import java.io.ByteArrayInputStream
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/** Immutable automatic inputs for exactly one open full-song dialog. */
internal data class FullSongLaunchSnapshot(
    val artist: String,
    val title: String,
    val artworkUrl: String?,
)

/**
 * Native music search surface backed by a hidden WebView.
 *
 * The browser remains the compatibility layer for the public web flow (cookies, JS and
 * browser behaviour), while users only see MediaLoader UI.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HitMozSearchDialog(
    initialArtist: String,
    initialTitle: String,
    onDismiss: () -> Unit,
    recognitionMessage: String? = null,
    recognizedArtworkUrl: String? = null,
    onSearchUnavailable: ((artist: String, title: String, reason: String) -> Unit)? = null,
    onOtherSource: ((artist: String, title: String) -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember(context) { MediaRepository.get(context) }
    val previewPlayer = rememberMusicPreviewPlayer()
    val downloads by repo.downloads.collectAsState(initial = emptyList())

    // Hard input lock: upstream recognizers and repository updates can supply
    // these values ONLY on the first composition of this window. A recomposition
    // with different initialArtist/initialTitle must never reset user edits.
    val launchSnapshot = remember {
        FullSongLaunchSnapshot(initialArtist, initialTitle, recognizedArtworkUrl)
    }
    // Only the two OutlinedTextField.onValueChange callbacks write these states.
    // Explicit user edits survive Activity recreation; external prop changes do not.
    var artist by rememberSaveable { mutableStateOf(launchSnapshot.artist) }
    var title by rememberSaveable { mutableStateOf(launchSnapshot.title) }
    var tracks by remember { mutableStateOf<List<HitMozTrack>>(emptyList()) }
    var currentSearch by rememberSaveable {
        mutableStateOf(
            if (launchSnapshot.artist.isBlank() && launchSnapshot.title.isBlank()) ""
            else HitMozSearchClient.searchUrl("${launchSnapshot.artist} ${launchSnapshot.title}")
        )
    }
    var nextPageUrl by remember { mutableStateOf<String?>(null) }
    var appendResults by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(currentSearch.isNotBlank()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var pendingBrowserTrack by remember { mutableStateOf<HitMozTrack?>(null) }
    var browserRequestId by remember { mutableIntStateOf(0) }
    var pendingSearchRevision by remember { mutableIntStateOf(-1) }
    var searchRevision by remember { mutableIntStateOf(0) }
    var extractionRevision by remember { mutableIntStateOf(-1) }
    var pageFinishedRevision by remember { mutableIntStateOf(-1) }
    var fallbackRevision by remember { mutableIntStateOf(-1) }
    var verifiedDomRevision by remember { mutableIntStateOf(-1) }
    var verifiedDomUrl by remember { mutableStateOf("") }
    var verifyingDomRevision by remember { mutableIntStateOf(-1) }

    // Only the active search can trigger an automatic handoff. Editing the fields or
    // loading another page cancels this timer; successful results keep HitMoz visible.
    LaunchedEffect(currentSearch, searchRevision, loading, tracks.isEmpty(), onSearchUnavailable) {
        if (onSearchUnavailable != null && currentSearch.isNotBlank() && loading &&
            tracks.isEmpty() && !appendResults
        ) {
            kotlinx.coroutines.delay(12_000L)
            if (loading && tracks.isEmpty() && !appendResults) {
                onSearchUnavailable(artist.trim(), title.trim(), "timeout")
            }
        }
    }
    LaunchedEffect(currentSearch, loading, loadError, onSearchUnavailable) {
        if (onSearchUnavailable != null && currentSearch.isNotBlank() && !loading &&
            tracks.isEmpty() && !appendResults && loadError != null
        ) {
            onSearchUnavailable(artist.trim(), title.trim(), "search-failed")
        }
    }

    fun belongsToCurrentSearch(url: String): Boolean =
        HitMozSearchPageGate.route(currentSearch, url) == HitMozSearchPageGate.Route.EXACT

    fun belongsToVerifiedDocument(url: String): Boolean =
        belongsToCurrentSearch(url) ||
            (verifiedDomRevision == searchRevision && verifiedDomUrl == url)

    fun acceptBrowserMp3(url: String, channel: String) {
        val selected = pendingBrowserTrack ?: run {
            AppLog.verbose("HitMoz", "Browser callback ignored · channel=$channel · reason=no-pending-selection · ${HitMozDownloadUrls.diagnosticAddress(url)}")
            return
        }
        // Never accept a browser callback from a prior search or from a different track.
        if (!HitMozSelectionGuard.accepts(
                pendingUrl = selected.downloadUrl,
                callbackUrl = url,
                pendingRevision = pendingSearchRevision,
                activeRevision = searchRevision,
                hasActiveSearch = currentSearch.isNotBlank(),
                loading = loading,
                selectedRowStillVisible = tracks.any { it.downloadUrl == selected.downloadUrl },
            )
        ) {
            AppLog.w("HitMoz", "Ignored stale or mismatched MP3 browser callback · channel=$channel")
            pendingBrowserTrack = null
            pendingSearchRevision = -1
            return
        }
        if (!HitMozDownloadUrls.isMp3(url)) {
            AppLog.w("HitMoz", "Ignored unexpected WebView download destination · channel=$channel")
            pendingBrowserTrack = null
            pendingSearchRevision = -1
            return
        }
        val selectedRevision = searchRevision
        val selectedSearch = currentSearch
        pendingBrowserTrack = null
        pendingSearchRevision = -1
        AppLog.verbose("HitMoz", "Browser MP3 handoff · requestId=$browserRequestId · channel=$channel · addressUpdated=${url != selected.downloadUrl} · ${HitMozDownloadUrls.diagnosticAddress(url)}")
        if (url != selected.downloadUrl) {
            tracks = tracks.map {
                if (it.downloadUrl == selected.downloadUrl) it.copy(downloadUrl = url) else it
            }
        }
        scope.launch {
            // A field edit can happen after the WebView callback but before queueing.
            if (searchRevision != selectedRevision || currentSearch != selectedSearch) return@launch
            try {
                val queued = repo.enqueueHitMozMp3(
                    url, selected.artist, selected.title, selected.artworkUrl,
                    sourcePageUrl = selectedSearch,
                )
                AppLog.i("HitMoz", "Browser-selected MP3 queued · id=${queued.id} · requestId=$browserRequestId · channel=$channel · ${HitMozDownloadUrls.diagnosticAddress(url)}")
                Toast.makeText(context, "Добавлено в загрузки", Toast.LENGTH_SHORT).show()
            } catch (failure: Exception) {
                AppLog.w("HitMoz", "Could not queue selected MP3", failure)
                Toast.makeText(context, failure.message ?: "Не удалось начать загрузку", Toast.LENGTH_LONG).show()
            }
        }
    }


    LaunchedEffect(context) { MusicAdFilter.initialize(context) }

    fun applyBrowserPayload(rawResult: String?, refresh: Boolean = false): Boolean {
        val decoded = runCatching { JSONTokener(rawResult.orEmpty()).nextValue() as? String }
            .getOrNull()
            ?: return false
        val payload = runCatching { JSONObject(decoded) }.getOrNull() ?: return false
        val array = payload.optJSONArray("tracks") ?: return false
        val parsed = buildList {
            for (index in 0 until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val downloadUrl = row.optString("downloadUrl").trim()
                if (!HitMozDownloadUrls.isMp3(downloadUrl)) continue
                val rowArtist = row.optString("artist").trim()
                val rowTitle = row.optString("title").trim()
                if (rowArtist.isBlank() || rowTitle.isBlank()) continue
                val pageUrl = row.optString("pageUrl").trim().takeIf(::isHitMozPage)
                    ?: HitMozSearchClient.searchUrl("$rowArtist $rowTitle")
                add(
                    HitMozTrack(
                        artist = rowArtist,
                        title = rowTitle,
                        duration = row.optString("duration").trim().ifBlank { null },
                        pageUrl = pageUrl,
                        downloadUrl = downloadUrl,
                        artworkUrl = launchSnapshot.artworkUrl?.takeIf { isSafeBrowserUrl(it) &&
                            matchesRecognizedMusic(launchSnapshot.artist, launchSnapshot.title, artist, title) &&
                            matchesRecognizedMusic(launchSnapshot.artist, launchSnapshot.title, rowArtist, rowTitle) }
                            ?: row.optString("artworkUrl").trim().takeIf(::isSafeBrowserUrl),
                    ),
                )
            }
        }.distinctBy { it.downloadUrl }
        if (parsed.isEmpty()) {
            AppLog.verboseSampled("HitMoz", "empty-rows:$searchRevision",
                "DOM scan found no usable songs · revision=$searchRevision · candidates=${array.length()} · ready=${payload.optString("readyState").take(20)}")
            return false
        }
        // An early DOM scan can see fewer rows than the finished page.
        if (refresh && parsed.size <= tracks.size) return true

        val incoming = if (appendResults) (tracks + parsed).distinctBy { it.downloadUrl }
            else parsed
        tracks = incoming.map { row ->
            if (row.artworkUrl != null) row
            else row.copy(artworkUrl = tracks.firstOrNull {
                it.downloadUrl == row.downloadUrl
            }?.artworkUrl)
        }
        appendResults = false
        nextPageUrl = payload.optString("nextPageUrl").trim().takeIf(::isHitMozPage)
        loading = false
        loadError = null
        AppLog.i("HitMoz", "Native music results parsed · count=${parsed.size}")
        AppLog.verbose("MusicArtwork", "Cover URLs in results: ${parsed.count { it.artworkUrl != null }}/${parsed.size} · recognizedCover=${parsed.count { it.artworkUrl != null && it.artworkUrl == recognizedArtworkUrl }}")
        // Catalog artwork is optional. Resolve in the background AFTER results are visible.
        // Use one bounded lookup per search, and never attribute art to a different song.
        if (parsed.any { it.artworkUrl == null } && artist.isNotBlank() && title.isNotBlank()) {
            val requestUrl = currentSearch
            val requestRevision = searchRevision
            val requestedArtist = artist
            val requestedTitle = title
            scope.launch {
                val cover = withContext(Dispatchers.IO) {
                    MusicArtworkLookup.lookup(requestedArtist, requestedTitle)
                }
                if (cover != null && currentSearch == requestUrl && searchRevision == requestRevision) {
                    tracks = tracks.map { row ->
                        if (row.artworkUrl == null &&
                            matchesRecognizedMusic(requestedArtist, requestedTitle, row.artist, row.title)
                        ) row.copy(artworkUrl = cover) else row
                    }
                    AppLog.verbose("MusicArtwork", "Catalog cover applied · matches=${tracks.count { it.artworkUrl == cover }}")
                }
            }
        }
        return true
    }

    fun recoverEmptyDomResults(revision: Int, expectedUrl: String) {
        // The website sometimes returns a complete page without usable song rows.
        // A single GET-based fallback may recover results; never show a prior query's data.
        if (fallbackRevision == revision) return
        fallbackRevision = revision
        if (appendResults) {
            loading = false
            loadError = "Не удалось загрузить следующую страницу. Повторите запрос."
            return
        }
        val requestedArtist = artist
        val requestedTitle = title
        scope.launch {
            AppLog.w("HitMoz", "DOM yielded no song rows · trying bounded HTTP fallback · revision=$revision")
            val result = runCatching {
                HitMozSearchClient().search(
                    requestedArtist, requestedTitle,
                    connectTimeoutMs = 4_000,
                    readTimeoutMs = 5_000,
                )
            }
            val recovered = result.getOrNull()
            if (result.isFailure) {
                AppLog.w("HitMoz", "HTTP search fallback failed · revision=$revision · reason=${result.exceptionOrNull()?.javaClass?.simpleName}")
            }
            if (!loading || searchRevision != revision || currentSearch != expectedUrl) return@launch
            if (!recovered.isNullOrEmpty()) {
                tracks = recovered
                loading = false
                appendResults = false
                loadError = null
                AppLog.i("HitMoz", "Fallback search recovered tracks · count=${recovered.size}")
            } else {
                loading = false
                appendResults = false
                loadError = "Не удалось получить результаты от музыкального сервиса. Повторите запрос."
                AppLog.w("HitMoz", "No usable search results from browser or HTTP fallback · revision=$revision")
            }
        }
    }

    fun extractNativeResults(view: WebView) {
        val expectedUrl = view.url ?: return
        if (!loading) return
        val revision = searchRevision
        val requestedSearch = currentSearch

        fun startVerifiedExtraction() {
            if (!loading || searchRevision != revision || currentSearch != requestedSearch ||
                view.url != expectedUrl || extractionRevision == revision
            ) return
            // Chrome progress and page-finish may fire many times per navigation.
            extractionRevision = revision
            AppLog.verbose("HitMoz", "Music extraction started · revision=$revision · waitingForPageFinish=true")
            fun tryExtract(attempt: Int) {
                if (!loading || searchRevision != revision || view.url != expectedUrl) return
                view.evaluateJavascript(NATIVE_RESULT_SCRIPT) { raw ->
                    if (!loading || searchRevision != revision || view.url != expectedUrl) return@evaluateJavascript
                    if (applyBrowserPayload(raw)) return@evaluateJavascript
                    if (attempt == 0 || attempt % 5 == 0) {
                        AppLog.verbose("HitMoz", "Music extraction awaiting rows · revision=$revision · attempt=${attempt + 1} · pageFinished=${pageFinishedRevision == revision}")
                    }
                    if (pageFinishedRevision != revision || attempt < 2) {
                        view.postDelayed({ tryExtract(attempt + 1) }, 650L)
                    } else {
                        AppLog.w("HitMoz", "Native music extraction empty after page finish · revision=$revision · attempts=${attempt + 1}")
                        recoverEmptyDomResults(revision, expectedUrl)
                    }
                }
            }
            tryExtract(0)
        }

        when (HitMozSearchPageGate.route(requestedSearch, expectedUrl)) {
            HitMozSearchPageGate.Route.EXACT -> startVerifiedExtraction()
            HitMozSearchPageGate.Route.NEED_DOM_PROOF -> {
                if (verifiedDomRevision == revision && verifiedDomUrl == expectedUrl) {
                    startVerifiedExtraction()
                } else if (verifyingDomRevision != revision) {
                    // Some site navigations POST to /search and lose ?q=. Only
                    // accept these pages if the CURRENT DOM proves the same query.
                    verifyingDomRevision = revision
                    val script = """
                        (function() {
                          var input = document.querySelector('input[name="q"], input[name="query"], input[type="search"]');
                          return input ? input.value : '';
                        })();
                    """.trimIndent()
                    view.evaluateJavascript(script) { raw ->
                        verifyingDomRevision = -1
                        if (!loading || searchRevision != revision || currentSearch != requestedSearch ||
                            view.url != expectedUrl
                        ) return@evaluateJavascript
                        val visibleQuery = runCatching {
                            JSONTokener(raw.orEmpty()).nextValue() as? String
                        }.getOrNull().orEmpty()
                        if (HitMozSearchPageGate.domQueryMatches(requestedSearch, visibleQuery)) {
                            verifiedDomRevision = revision
                            verifiedDomUrl = expectedUrl
                            AppLog.verbose("HitMoz", "Search document verified · method=dom-query · revision=$revision")
                            startVerifiedExtraction()
                        } else {
                            AppLog.verboseSampled("HitMoz", "query-proof:$revision",
                                "Search document rejected · reason=unverified-form-query · revision=$revision")
                        }
                    }
                }
            }
            HitMozSearchPageGate.Route.REJECT -> {
                AppLog.verboseSampled("HitMoz", "route-reject:$revision",
                    "Search document ignored · reason=url-does-not-match-active-search · revision=$revision")
            }
        }
    }

    val web = remember(context) {
        WebView(context).apply {
            CookieManager.getInstance().setAcceptCookie(true)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                safeBrowsingEnabled = true
                userAgentString = userAgentString
                    .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
            }
            context.getSharedPreferences("ui", 0).edit()
                .putString("hitmoz_browser_user_agent", settings.userAgentString)
                .apply()
            AppLog.verbose("HitMoz", "WebView browser agent synchronized")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    AppLog.verbose("HitMozWebView", SafeWebConsole.describe(message))
                    return true
                }
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress / 25 > progress / 25) {
                        AppLog.verbose("HitMoz", "WebView progress · revision=$searchRevision · percent=$newProgress · loading=$loading")
                    }
                    progress = newProgress
                    // The song rows often render long before analytics/ads finish loading.
                    if (newProgress >= 35 && loading && isHitMozPage(view.url.orEmpty())) {
                        extractNativeResults(view)
                    }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    val destination = request.url.toString()
                    AppLog.verbose("HitMoz", "Browser navigation · method=${request.method} · gesture=${request.hasGesture()} · redirect=${request.isRedirect} · ${HitMozDownloadUrls.diagnosticAddress(destination)}")
                    if (HitMozDownloadUrls.isMp3(destination)) {
                        acceptBrowserMp3(destination, "webview-navigation")
                        return true
                    }
                    return !isHitMozPage(destination)
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (currentSearch.isBlank()) {
                        AppLog.verbose("HitMoz", "Ignored page start after search input changed")
                        view.stopLoading()
                        return
                    }
                    val route = HitMozSearchPageGate.route(currentSearch, url.orEmpty())
                    if (route != HitMozSearchPageGate.Route.EXACT) {
                        AppLog.verbose("HitMoz",
                            "Noncanonical search page start · route=${route.name} · revision=$searchRevision")
                        return
                    }
                    if (!loading && tracks.isNotEmpty()) {
                        // Reloading the same hidden browser document must not reset visible results.
                        AppLog.verbose("HitMoz", "Duplicate page start retained existing music results")
                        return
                    }
                    MusicAdFilter.onPageStarted(view, url)
                    searchRevision += 1
                    extractionRevision = -1
                    pageFinishedRevision = -1
                    fallbackRevision = -1
                    loading = true
                    progress = 0
                    loadError = null
                    AppLog.i("HitMoz", "Search document started · revision=$searchRevision · route=${route.name} · ${HitMozDownloadUrls.diagnosticAddress(url.orEmpty())}")
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    val route = HitMozSearchPageGate.route(currentSearch, url.orEmpty())
                    AppLog.verbose("HitMoz", "WebView page finished · hitmoz=${isHitMozPage(url.orEmpty())} · route=${route.name} · cookies=${!CookieManager.getInstance().getCookie("https://ru.hitmoz.org").isNullOrBlank()}")
                    if (!isHitMozPage(url.orEmpty())) return
                    if (loading && route != HitMozSearchPageGate.Route.REJECT) {
                        pageFinishedRevision = searchRevision
                    }
                    view.evaluateJavascript(musicAdCleanupScript, null)
                    if (loading) {
                        extractNativeResults(view)
                    } else if (belongsToVerifiedDocument(view.url.orEmpty()) && tracks.isNotEmpty()) {
                        // Refresh any songs added after early extraction. Never hide results.
                        view.evaluateJavascript(NATIVE_RESULT_SCRIPT) { result ->
                            if (belongsToVerifiedDocument(view.url.orEmpty()) && !loading) {
                                applyBrowserPayload(result, refresh = true)
                            }
                        }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView,
                    request: WebResourceRequest,
                ): WebResourceResponse? {
                    val address = request.url.toString()
                    val musicDetails = if (HitMozDownloadUrls.isMp3(address)) {
                        val headerNames = request.requestHeaders.keys.sorted().take(12).joinToString(",")
                        " · method=${request.method} · gesture=${request.hasGesture()} · redirect=${request.isRedirect} · explicitHeaders=$headerNames · ${HitMozDownloadUrls.diagnosticAddress(address)}"
                    } else ""
                    if (HitMozDownloadUrls.isMp3(address)) {
                        AppLog.verbose("MusicWebView", "Resource request · host=${request.url.host.orEmpty()} · mainFrame=${request.isForMainFrame}$musicDetails")
                    } else {
                        AppLog.verboseSampled(
                            "MusicWebView",
                            "resource:${request.url.host.orEmpty()}:${request.isForMainFrame}",
                            "Resource request · host=${request.url.host.orEmpty()} · mainFrame=${request.isForMainFrame}",
                        )
                    }
                    if (musicAdHost(request.url.host.orEmpty())) {
                        return WebResourceResponse(
                            "text/plain",
                            "UTF-8",
                            ByteArrayInputStream(ByteArray(0)),
                        )
                    }
                    return MusicAdFilter.intercept(view, request)
                        ?: super.shouldInterceptRequest(view, request)
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (!request.isForMainFrame || !loading) return
                    // A timeout can fire while usable song rows are already in the DOM.
                    // Attempt extraction rather than prematurely erasing a valid search.
                    if (error.errorCode == WebViewClient.ERROR_TIMEOUT) {
                        AppLog.w("HitMoz", "WebView timed out · checking available music results")
                        pageFinishedRevision = searchRevision
                        extractNativeResults(view)
                    } else {
                        loading = false
                        searchRevision += 1
                        extractionRevision = -1
                        loadError = "Не удалось загрузить результаты поиска."
                        AppLog.e("HitMoz", "Hidden WebView error: ${error.errorCode}")
                    }
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (errorResponse.statusCode >= 400) {
                        AppLog.verbose("MusicWebView", "HTTP error · host=${request.url.host.orEmpty()} · code=${errorResponse.statusCode} · mainFrame=${request.isForMainFrame} · method=${request.method} · mime=${errorResponse.mimeType.orEmpty().take(40)} · location=${errorResponse.responseHeaders?.containsKey("Location") == true} · ${HitMozDownloadUrls.diagnosticAddress(request.url.toString())}")
                    }
                    if (request.isForMainFrame && errorResponse.statusCode >= 400 && loading) {
                        loading = false
                        searchRevision += 1
                        extractionRevision = -1
                        loadError = "Поиск временно недоступен (HTTP ${errorResponse.statusCode})."
                        AppLog.e("HitMoz", "Hidden WebView HTTP ${errorResponse.statusCode}")
                    }
                }
            }
            MusicAdFilter.setup(this)
            setDownloadListener { url, agent, disposition, mimeType, length ->
                AppLog.verbose("HitMoz", "WebView DownloadListener · mp3=${HitMozDownloadUrls.isMp3(url)} · mime=${mimeType.orEmpty().take(60)} · length=$length · disposition=${!disposition.isNullOrBlank()} · agentMatches=${agent == settings.userAgentString} · ${HitMozDownloadUrls.diagnosticAddress(url)}")
                acceptBrowserMp3(url, "webview-download-listener")
            }
        }
    }

    fun downloadThroughBrowser(track: HitMozTrack) {
        if (pendingBrowserTrack != null || currentSearch.isBlank() || loading ||
            tracks.none { it.downloadUrl == track.downloadUrl }
        ) {
            AppLog.w("HitMoz", "Download ignored · no active result selection")
            return
        }
        pendingBrowserTrack = track
        pendingSearchRevision = searchRevision
        browserRequestId += 1
        val requestId = browserRequestId
        AppLog.verbose("HitMoz", "Native download pressed · requestId=$requestId · starting browser link action · ${HitMozDownloadUrls.diagnosticAddress(track.downloadUrl)}")
        // Reuse the chosen anchor in WebView so site click handlers can run.
        val target = JSONObject.quote(track.downloadUrl)
        val script = """
            (function() {
              var target = $target;
              var links = document.querySelectorAll('a[href*="/get/music/"]');
              for (var i = 0; i < links.length; i++) {
                var anchor = links[i];
                try {
                  if (new URL(anchor.getAttribute('href'), location.href).href === target) {
                    anchor.click();
                    return 'clicked';
                  }
                } catch (_) {}
              }
              return 'missing';
            })();
        """.trimIndent()
        web.evaluateJavascript(script) { result ->
            AppLog.verbose("HitMoz", "Browser link action · requestId=$requestId · result=${result.take(40)}")
            // WebView may not deliver a callback for a script click; do not hang.
            web.postDelayed({
                if (pendingBrowserTrack?.downloadUrl == track.downloadUrl &&
                    requestId == browserRequestId
                ) {
                    AppLog.verbose("HitMoz", "No WebView MP3 callback · requestId=$requestId · HTTP handoff fallback")
                    acceptBrowserMp3(track.downloadUrl, "browser-fallback")
                }
            }, 1_800L)
        }
    }

    DisposableEffect(web) {
        onDispose {
            pendingBrowserTrack = null
            pendingSearchRevision = -1
            web.stopLoading()
            web.setDownloadListener(null)
            web.webChromeClient = WebChromeClient()
            web.webViewClient = WebViewClient()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }

    LaunchedEffect(web) {
        if (currentSearch.isNotBlank()) {
            AppLog.i("HitMoz", "Initial browser search requested · revision=$searchRevision · ${HitMozDownloadUrls.diagnosticAddress(currentSearch)}")
            web.loadUrl(currentSearch)
        } else {
            loading = false
        }
    }

    fun invalidateSearchAfterEdit() {
        previewPlayer.stop()
        // Typing is never a download or a search action. Invalidate pending WebView work.
        browserRequestId += 1
        pendingBrowserTrack = null
        pendingSearchRevision = -1
        searchRevision += 1
        currentSearch = ""
        tracks = emptyList()
        nextPageUrl = null
        appendResults = false
        loading = false
        loadError = null
        extractionRevision = -1
        pageFinishedRevision = -1
        fallbackRevision = -1
        verifiedDomRevision = -1
        verifiedDomUrl = ""
        verifyingDomRevision = -1
        web.stopLoading()
    }

    fun submitSearch() {
        previewPlayer.stop()
        val query = listOf(artist.trim(), title.trim())
            .filter { it.isNotBlank() }
            .joinToString(" ")
        if (query.isBlank()) return
        pendingBrowserTrack = null
        pendingSearchRevision = -1
        browserRequestId += 1
        tracks = emptyList()
        nextPageUrl = null
        appendResults = false
        loadError = null
        loading = true
        searchRevision += 1
        extractionRevision = -1
        pageFinishedRevision = -1
        fallbackRevision = -1
        currentSearch = HitMozSearchClient.searchUrl(query)
        verifiedDomRevision = -1
        verifiedDomUrl = ""
        verifyingDomRevision = -1
        AppLog.i("HitMoz", "Native music search submitted")
        web.loadUrl(currentSearch)
    }

    fun loadMore() {
        val next = nextPageUrl ?: return
        if (pendingBrowserTrack != null) return
        currentSearch = next
        verifiedDomRevision = -1
        verifiedDomUrl = ""
        verifyingDomRevision = -1
        appendResults = true
        nextPageUrl = null
        loading = true
        searchRevision += 1
        extractionRevision = -1
        pageFinishedRevision = -1
        fallbackRevision = -1
        web.loadUrl(next)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        com.domenota.medialoader.ui.theme.TransparentWindowNavigation()
        BackHandler(onBack = onDismiss)
        Surface(
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f),
            tonalElevation = 4.dp,
        ) {
            Box {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Поиск музыки", style = MaterialTheme.typography.titleMedium)
                            Text(
                                recognitionMessage ?: "Основной источник",
                                style = MaterialTheme.typography.bodySmall,
                                color = when {
                                    recognitionMessage?.startsWith("Трек распознан") == true ->
                                        MaterialTheme.colorScheme.primary
                                    recognitionMessage != null -> MaterialTheme.colorScheme.error
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        TextButton(onClick = onDismiss) { Text("Закрыть") }
                    }

                    OutlinedTextField(
                        value = artist,
                        onValueChange = {
                            if (artist != it) {
                                artist = it
                                invalidateSearchAfterEdit()
                            }
                        },
                        label = { Text("Исполнитель") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = title,
                        onValueChange = {
                            if (title != it) {
                                title = it
                                invalidateSearchAfterEdit()
                            }
                        },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = ::submitSearch,
                            enabled = artist.isNotBlank() || title.isNotBlank(),
                        ) { Text("Найти") }
                        if (onOtherSource != null) TextButton(
                            onClick = { onOtherSource(artist.trim(), title.trim()) },
                        ) { Text("Другой источник") }
                    }

                    HorizontalDivider()
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    loadError?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    previewPlayer.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }

                    when {
                        currentSearch.isBlank() -> {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Введите название или исполнителя.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        tracks.isEmpty() && loading -> {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "Ищем подходящие версии…",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(tracks, key = { it.downloadUrl }) { track ->
                                    // Resolve only currently visible song covers; the catalog
                                    // must match both song name and artist, not just the search term.
                                    if (track.artworkUrl == null) {
                                        LaunchedEffect(track.downloadUrl, searchRevision) {
                                            val requestedUrl = currentSearch
                                            val revision = searchRevision
                                            val cover = withContext(Dispatchers.IO) {
                                                MusicArtworkLookup.lookup(track.artist, track.title)
                                            }
                                            if (cover != null && currentSearch == requestedUrl &&
                                                searchRevision == revision
                                            ) {
                                                tracks = tracks.map { row ->
                                                    if (row.downloadUrl == track.downloadUrl &&
                                                        row.artworkUrl == null
                                                    ) row.copy(artworkUrl = cover) else row
                                                }
                                            }
                                        }
                                    }
                                    val task = downloads
                                        .filter {
                                            it.providerId == "hitmoz" &&
                                                it.sourceUrl == track.downloadUrl
                                        }
                                        .maxByOrNull { it.createdAtEpochMillis }
                                    NativeMusicTrackRow(
                                        track = track,
                                        state = task?.state,
                                        preview = previewPlayer,
                                        previewPlaying = previewPlayer.currentUrl == track.downloadUrl &&
                                            previewPlayer.playing,
                                        previewBuffering = previewPlayer.currentUrl == track.downloadUrl &&
                                            previewPlayer.buffering,
                                        onPreview = {
                                            if (HitMozDownloadUrls.isMp3(track.downloadUrl))
                                                previewPlayer.toggle(track.downloadUrl)
                                        },
                                        onDownload = { downloadThroughBrowser(track) },
                                    )
                                }
                                if (nextPageUrl != null) {
                                    item {
                                        TextButton(
                                            onClick = ::loadMore,
                                            enabled = !loading,
                                            modifier = Modifier.fillMaxWidth(),
                                        ) {
                                            Text("Показать ещё")
                                        }
                                    }
                                }
                                item { Spacer(Modifier.height(4.dp)) }
                            }
                        }
                    }
                }

                AndroidView(
                    factory = { web },
                    modifier = Modifier
                        .size(1.dp)
                        .alpha(0f)
                        .align(Alignment.BottomEnd),
                )
            }
        }
    }
}

@Composable
private fun NativeMusicTrackRow(
    track: HitMozTrack,
    state: DownloadState?,
    preview: MusicPreviewPlayer,
    previewPlaying: Boolean,
    previewBuffering: Boolean,
    onPreview: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.size(54.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null)
                    if (track.artworkUrl != null) {
                        RemotePreview(track.artworkUrl, Modifier.fillMaxSize(), sourceHint = "hitmoz")
                    }
                }
            }

            Column(Modifier.weight(1f)) {
                Text(
                    track.title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                track.duration?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (preview.currentUrl == track.downloadUrl) {
                    MusicPreviewTimeline(preview)
                }
            }

            Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                if (previewBuffering) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else IconButton(onClick = onPreview, modifier = Modifier.size(42.dp)) {
                    Icon(if (previewPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (previewPlaying) "Пауза" else "Прослушать")
                }
            }

            // The action has the same width in every state. A progress label inside
            // this slot would change the row's horizontal layout while downloading.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    DownloadState.QUEUED, DownloadState.RUNNING ->
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    DownloadState.COMPLETED ->
                        Icon(
                            Icons.Rounded.CheckCircle,
                            contentDescription = "Скачано",
                            modifier = Modifier.size(24.dp),
                            tint = if (MaterialTheme.colorScheme.background.luminance() > 0.5f)
                                Color(0xFF15964A) else Color(0xFF65D49A),
                        )
                    DownloadState.FAILED, DownloadState.CANCELLED ->
                        IconButton(onClick = onDownload) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = "Повторить",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    null -> IconButton(onClick = onDownload) {
                        Icon(Icons.Rounded.Download, contentDescription = "Скачать")
                    }
                }
            }
        }
    }
}

/** Apply Shazam artwork only to an exact artist/title match, never to remixes or covers. */
internal fun matchesRecognizedMusic(
    recognizedArtist: String,
    recognizedTitle: String,
    resultArtist: String,
    resultTitle: String,
): Boolean {
    fun normalize(value: String): String = value.lowercase(java.util.Locale.ROOT)
        .replace("&", " and ")
        .filter { it.isLetterOrDigit() }
    return recognizedArtist.isNotBlank() && recognizedTitle.isNotBlank() &&
        normalize(recognizedArtist) == normalize(resultArtist) &&
        normalize(recognizedTitle) == normalize(resultTitle)
}

internal fun isSafeBrowserUrl(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}.getOrDefault(false)

internal fun isHitMozPage(url: String): Boolean = runCatching {
    val uri = Uri.parse(url)
    val host = uri.host?.lowercase().orEmpty()
    uri.scheme.equals("https", ignoreCase = true) &&
        (host == "ru.hitmoz.org" || host.endsWith(".ru.hitmoz.org"))
}.getOrDefault(false)

internal fun musicAdHost(host: String): Boolean {
    val normalized = host.lowercase(java.util.Locale.ROOT)
    return listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "yandexadexchange.net", "adfox.ru", "an.yandex.ru",
        "adriver.ru", "adnxs.com", "adform.net", "taboola.com",
        "outbrain.com",
    ).any { normalized == it || normalized.endsWith(".$it") }
}

private val NATIVE_RESULT_SCRIPT = """
(function() {
  function text(node, selector) {
    var found = node.querySelector(selector);
    return found ? (found.textContent || '').replace(/\s+/g, ' ').trim() : '';
  }
  function absolute(raw) {
    if (!raw) return '';
    try { return new URL(raw, location.href).href; } catch (_) { return ''; }
  }
  function artwork(node) {
    if (!node || !node.querySelectorAll) return '';
    var candidates = [node].concat(Array.prototype.slice.call(node.querySelectorAll(
      '.track__img, .track__image, .track__cover, .song__cover, ' +
      'img, source[srcset], picture, [itemprop="image"], ' +
      '[data-background-image], [data-cover], [data-image], ' +
      '[data-original], [data-src], [style*="background"]'
    ), 0, 32));
    for (var j = 0; j < candidates.length; j++) {
      var el = candidates[j];
      if (typeof el.getAttribute !== 'function') continue;
      var background = el.getAttribute('style') || '';
      // Some music cards set the cover from an external CSS rule rather
      // than an inline style or an <img> element.
      if (typeof getComputedStyle === 'function') {
        try { background += ';' + getComputedStyle(el).backgroundImage; } catch (_) {}
      }
      var cssMatch = background.match(/url\(\s*['"]?([^'")]+)/i);
      var sourceSet = el.getAttribute('data-srcset') || el.getAttribute('srcset') || '';
      var firstSource = sourceSet.split(',')[0].trim().split(/\s+/)[0] || '';
      var urls = [
        el.getAttribute('data-cover'), el.getAttribute('data-image'),
        el.getAttribute('data-artwork'), el.getAttribute('data-background-image'),
        el.getAttribute('data-src'), el.getAttribute('data-original'),
        el.getAttribute('data-lazy-src'), el.getAttribute('data-poster'),
        el.currentSrc, firstSource, el.getAttribute('src'),
        el.getAttribute('content'), cssMatch ? cssMatch[1] : ''
      ];
      for (var k = 0; k < urls.length; k++) {
        var url = absolute(urls[k]);
        if (url.indexOf('https://') === 0 && !/blank|placeholder|spacer|pixel/i.test(url)) {
          return url;
        }
      }
    }
    return '';
  }
  var rows = [];
  var seen = {};
  var links = document.querySelectorAll('a[href*="/get/music/"]');
  for (var i = 0; i < links.length; i++) {
    var download = links[i];
    var href = absolute(download.getAttribute('href'));
    if (!href || seen[href]) continue;
    var row = download;
    for (var depth = 0; depth < 8 && row && row !== document.body; depth++) {
      if (row.querySelector &&
          (row.querySelector('.track__title, .song__title, .track-title, [itemprop="name"]') ||
           row.classList.contains('track') ||
           row.classList.contains('tracks__item'))) break;
      row = row.parentElement;
    }
    row = row || download.parentElement || download;
    var title = text(row, '.track__title, .song__title, .track-title, [itemprop="name"]');
    var artist = text(row, '.track__desc, .song__artist, .track__artist, .track-artist, [itemprop="byArtist"]');
    var duration = text(row, '.track__fulltime, .song__duration, .track__duration, time');
    var song = row.querySelector ? row.querySelector('a[href*="/song/"]') : null;
    var imageUrl = artwork(row);
    if ((!title || !artist) && href) {
      try {
        var filename = decodeURIComponent(new URL(href).pathname.split('/').pop() || '')
          .replace(/\.mp3$/i, '')
          .replace(/_\d{6,}$/, '');
        var parts = filename.split('_-_');
        if (parts.length >= 2) {
          if (!artist) artist = parts[0].replace(/_/g, ' ').trim();
          if (!title) title = parts.slice(1).join('_-_').replace(/_/g, ' ').trim();
        }
      } catch (_) {}
    }
    if (!title || !artist) continue;
    seen[href] = true;
    rows.push({
      title: title,
      artist: artist,
      duration: duration,
      downloadUrl: href,
      pageUrl: song ? absolute(song.getAttribute('href')) : '',
      artworkUrl: imageUrl
    });
  }
  var current = new URL(location.href);
  var currentStart = parseInt((current.pathname.match(/\/search\/start\/(\d+)/) || [,'0'])[1], 10) || 0;
  var next = '';
  var pagination = document.querySelectorAll('a[href*="/search/start/"]');
  var bestStart = Number.MAX_SAFE_INTEGER;
  for (var p = 0; p < pagination.length; p++) {
    try {
      var candidate = new URL(pagination[p].href, location.href);
      var match = candidate.pathname.match(/\/search\/start\/(\d+)/);
      var start = match ? parseInt(match[1], 10) : -1;
      if (start > currentStart && start < bestStart) {
        bestStart = start;
        next = candidate.href;
      }
    } catch (_) {}
  }
  return JSON.stringify({ tracks: rows, nextPageUrl: next, readyState: document.readyState });
})();
""".trimIndent()

private val musicAdCleanupScript = """
(function() {
  if (window.__musicAdCleanupInstalled) return;
  window.__musicAdCleanupInstalled = true;
  var selectors = [
    'iframe[src*="yandex"]', 'iframe[src*="adfox"]',
    'iframe[src*="doubleclick"]', 'iframe[src*="googleads"]',
    'ins.adsbygoogle', '.adsbygoogle', '[id^="yandex_rtb_"]',
    '[id*="adfox"]', '[data-ad-slot]', '[data-ad-client]',
    '.yandex-ad', '.ad-banner', '.advertisement',
    '[class*="yandex-rtb"]', '[class*="ya-ad-"]', '.rsya',
    '[class*="gift-popup"]', '[id*="gift-popup"]',
    '[class*="reward-widget"]', '[class*="bonus-widget"]',
    '[class*="push-subscribe"]', '[class*="floating-ad"]'
  ].join(',');
  function hasMusicControls(node) {
    return !!node.querySelector('a[href*="/get/music/"], .track__title, .track__fulltime');
  }
  function hide(node) {
    if (!node || !node.style) return;
    node.style.setProperty('display', 'none', 'important');
  }
  function clean() {
    document.querySelectorAll(selectors).forEach(function(node) {
      var parent = node.parentElement;
      hide(node);
      if (parent && parent.children.length === 1 && !hasMusicControls(parent)) hide(parent);
    });
  }
  clean();
  var pending = false;
  new MutationObserver(function() {
    if (pending) return;
    pending = true;
    requestAnimationFrame(function() { pending = false; clean(); });
  }).observe(document.documentElement, { childList: true, subtree: true });
})();
""".trimIndent()
