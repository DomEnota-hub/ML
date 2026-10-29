package com.domenota.medialoader.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.core.logging.SafeWebConsole
import com.domenota.medialoader.core.provider.InstagramLinkParser
import com.domenota.medialoader.core.provider.RutubeLinkParser
import com.domenota.medialoader.core.provider.VkLinkParser
import com.domenota.medialoader.core.provider.YouTubeLinkParser
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.data.rutube.RutubeYtDlpRuntime
import com.domenota.medialoader.core.diagnostics.AccountBrowserPolicy
import com.domenota.medialoader.data.vk.VkYtDlpRuntime
import com.domenota.medialoader.data.youtube.YouTubeLocalProof
import com.domenota.medialoader.data.youtube.YoutubeDlAndroid
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener


/** The browser shares cookies with the existing sign-in WebViews. Only validated links reach analysis. */
enum class BrowserSource(val id: String, val title: String, val startUrl: String) {
    INSTAGRAM("instagram", "Instagram", "https://www.instagram.com/"),
    YOUTUBE("youtube", "YouTube", "https://m.youtube.com/"),
    VK("vk", "VK", "https://m.vk.com/"),
    RUTUBE("rutube", "RUTUBE", "https://rutube.ru/");

    fun isSite(url: String?): Boolean {
        val uri = Uri.parse(url ?: return false)
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val domains = when (this) {
            INSTAGRAM -> listOf("instagram.com")
            YOUTUBE -> listOf("youtube.com")
            VK -> listOf("vk.com", "vk.ru", "vkvideo.ru")
            RUTUBE -> listOf("rutube.ru")
        }
        return domains.any { host == it || host.endsWith(".$it") }
    }

    fun allowsNavigation(url: String?): Boolean {
        if (isSite(url)) return true
        if (this == RUTUBE) return url?.let(AccountBrowserPolicy::allowsRutubeNavigation) == true
        val uri = Uri.parse(url ?: return false)
        if (uri.scheme != "https") return false
        val host = uri.host?.lowercase() ?: return false
        val authDomains = when (this) {
            INSTAGRAM -> listOf("facebook.com")
            YOUTUBE -> listOf("google.com", "gstatic.com", "googleusercontent.com")
            VK -> listOf("vkid.ru")
            RUTUBE -> listOf("gid.ru")
        }
        return authDomains.any { host == it || host.endsWith(".$it") }
    }

    fun acceptedLink(url: String): String? {
        if (!isSite(url)) return null
        return when (this) {
            INSTAGRAM -> {
                val normalized = Uri.parse(url).buildUpon().authority("www.instagram.com").build().toString()
                InstagramLinkParser.parse(normalized)?.let { normalized }
            }
            YOUTUBE -> YouTubeLinkParser.parse(url)?.canonicalUrl()
            VK -> VkLinkParser.parse(url)?.canonicalUrl()
            RUTUBE -> RutubeLinkParser.parse(url)?.canonicalUrl()
        }
    }

    companion object {
        fun fromId(id: String?): BrowserSource? = entries.firstOrNull { it.id == id }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SourceBrowserScreen(
    source: BrowserSource,
    onBack: () -> Unit,
    onDownload: (String) -> Unit,
    pickerVisible: Boolean = false,
    onRutubeSessionCaptured: () -> Unit = {},
) {
    val context = LocalContext.current
    val currentDownload by rememberUpdatedState(onDownload)
    val currentRutubeSessionCaptured by rememberUpdatedState(onRutubeSessionCaptured)
    var progress by remember { mutableIntStateOf(0) }
    var pageUrl by remember(source) { mutableStateOf(source.startUrl) }
    var navigationError by remember(source) { mutableStateOf<String?>(null) }
    var awaitingVkApp by remember(source) { mutableStateOf(false) }
    var captureVkSessionAfterApp by remember(source) { mutableStateOf(false) }
    var instagramCookieCaptured by remember(source) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val web = remember(source) {
        WebView(context).apply {
            val view = this
            val cookies = CookieManager.getInstance()
            cookies.setAcceptCookie(true)
            cookies.setAcceptThirdPartyCookies(view, true)
            if (source == BrowserSource.VK) VkYtDlpRuntime(context).restoreWebSession(cookies)
            settings.apply {
                userAgentString = userAgentString
                    .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
            addJavascriptInterface(object {
                @JavascriptInterface fun location(url: String) {
                    view.post {
                        if (source.isSite(url)) {
                            pageUrl = url
                        }
                    }
                }
                @JavascriptInterface fun debug(message: String) {
                    AppLog.i("Browser", "${source.id} · $message")
                }
                @JavascriptInterface fun localProofReady() {
                    view.post {
                        if (source != BrowserSource.YOUTUBE || !source.isSite(view.url)) return@post
                        view.evaluateJavascript(
                            "(function(){var p=window.__mediaLoaderProof;delete window.__mediaLoaderProof;" +
                                "return p?JSON.stringify(p):null})()",
                        ) { encoded ->
                            val status = runCatching {
                                val payload = JSONTokener(encoded).nextValue() as? String
                                    ?: return@runCatching "decode"
                                val proof = JSONObject(payload)
                                val visitor = proof.optString("visitorData")
                                val token = proof.optString("token")
                                YouTubeLocalProof.rejectionReason(visitor, token)
                                    ?: if (YouTubeLocalProof.offer(visitor, token)) "accepted" else "rejected"
                            }.getOrDefault("decode")
                            val accepted = status == "accepted"
                            AppLog.i("YouTube", "Local token bridge · accepted=$accepted · reason=$status")
                        }
                    }
                }
                @JavascriptInterface fun pick(link: String) {
                    view.post {
                        if (!source.isSite(view.url)) return@post
                        val accepted = source.acceptedLink(link)
                        if (accepted == null) {
                            Toast.makeText(context, "Откройте публикацию или видео для загрузки", Toast.LENGTH_SHORT).show()
                            return@post
                        }
                        runCatching { syncSession(source, context.applicationContext) }
                        currentDownload(accepted)
                    }
                }
            }, "MediaLoaderBridge")
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    AppLog.verbose("BrowserWebView", "source=${source.id} · " + SafeWebConsole.describe(message))
                    return true
                }
                override fun onProgressChanged(view: WebView, newProgress: Int) { progress = newProgress }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    val address = request.url.toString()
                    val allowed = source.allowsNavigation(address)
                    val appTarget = if (!allowed && source == BrowserSource.VK)
                        VkAuthNavigationRecovery.officialAppTarget(address) else null
                    val recovery = if (!allowed && appTarget == null && source == BrowserSource.VK)
                        VkAuthNavigationRecovery.secureTarget(address) else null
                    val openedApp = appTarget?.let { VkAuthIntentLauncher.open(context, it) } == true
                    if (openedApp) awaitingVkApp = true
                    val scheme = when (request.url.scheme?.lowercase()) {
                        "https" -> "https"
                        "http" -> "http"
                        "intent" -> "intent"
                        else -> "other"
                    }
                    if (source == BrowserSource.VK && scheme == "intent")
                        AppLog.i("BrowserWebView", "VK QR handoff · " +
                            VkAuthNavigationRecovery.safeIntentSummary(address))
                    AppLog.verbose("BrowserWebView", "Navigation · source=${source.id} · host=${request.url.host.orEmpty()} · scheme=$scheme · mainFrame=true · action=${when {
                        allowed -> "allow"
                        recovery != null -> "upgrade"
                        openedApp -> "official-app"
                        appTarget != null -> "app-unavailable"
                        else -> "block"
                    }}")
                    if (recovery != null) {
                        view.loadUrl(recovery)
                        return true
                    }
                    if (openedApp) return true
                    if (appTarget != null) {
                        navigationError = "VK не открылся для подтверждения входа. Вернитесь к странице входа и выберите вход по телефону или почте."
                        return true
                    }
                    if (!allowed) navigationError = "${source.title}: сайт запросил переход, который встроенный браузер не смог открыть. Вернитесь назад и повторите попытку."
                    return !allowed
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    AppLog.verbose("BrowserWebView", "HTTP error · source=${source.id} · host=${request.url.host.orEmpty()} · mainFrame=${request.isForMainFrame} · status=${response.statusCode}")
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    AppLog.w("BrowserWebView", "Network error · source=${source.id} · host=${request.url.host.orEmpty()} · mainFrame=${request.isForMainFrame} · code=${error.errorCode}")
                    if (request.isForMainFrame) navigationError = "Страница не загрузилась. Проверьте сеть и повторите вход."
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    AppLog.verbose("BrowserWebView", "Page started · source=${source.id} · host=${Uri.parse(url.orEmpty()).host.orEmpty()}")
                    progress = 0
                    navigationError = null
                    pageUrl = url.orEmpty()
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    AppLog.verbose("BrowserWebView", "Page finished · source=${source.id} · host=${Uri.parse(url.orEmpty()).host.orEmpty()}")
                    pageUrl = url.orEmpty()
                    if (source == BrowserSource.INSTAGRAM && source.isSite(url) &&
                        !Uri.parse(url.orEmpty()).path.orEmpty().startsWith("/accounts/login")) {
                        if (MediaRepository.get(context.applicationContext).sessions.syncFromWebView() &&
                            !instagramCookieCaptured) {
                            instagramCookieCaptured = true
                            AppLog.i("InstagramSession", "Browser session cookies captured")
                        }
                    }
                    if (source == BrowserSource.RUTUBE && source.isSite(url)) {
                        view.evaluateJavascript(AccountBrowserPolicy.rutubeProfileScript) { evidence ->
                            if (AccountBrowserPolicy.rutubeProfileConfirmed(evidence)) {
                                syncSession(source, context.applicationContext)
                                currentRutubeSessionCaptured()
                            }
                        }
                    }
                    if (source == BrowserSource.VK && captureVkSessionAfterApp &&
                        source.isSite(url) &&
                        Uri.parse(url.orEmpty()).host?.lowercase() in setOf("vk.com", "m.vk.com", "vk.ru", "m.vk.ru") &&
                        !Uri.parse(url.orEmpty()).path.orEmpty().contains("login", ignoreCase = true)) {
                        val saved = VkYtDlpRuntime(context.applicationContext)
                            .captureWebSession(CookieManager.getInstance())
                        AppLog.i("VKAuth", "Post-app browser session capture · saved=$saved")
                        if (saved) captureVkSessionAfterApp = false
                    }
                    if (source.isSite(url)) {
                        view.evaluateJavascript(
                            browserScript.replace("__SOURCE__", source.id) +
                                "\nwindow.__mediaLoaderInstalled",
                        ) { outcome ->
                            AppLog.i(
                                "Browser",
                                "${source.id} · injection=${outcome.take(64)}",
                            )
                        }
                    }
                }
            }
            loadUrl(source.startUrl)
        }
    }
    DisposableEffect(web, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && awaitingVkApp) {
                awaitingVkApp = false
                captureVkSessionAfterApp = true
                AppLog.i("BrowserWebView", "VK app returned · login page preserved")
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var browserPaused by remember(web) { mutableStateOf(false) }
    var timersPaused by remember(web) { mutableStateOf(false) }
    LaunchedEffect(web, pickerVisible) {
        if (source == BrowserSource.YOUTUBE && pickerVisible) {
            // The picker covers the browser, but the WebView otherwise keeps
            // rendering and playing media while yt-dlp analyzes the same page.
            web.evaluateJavascript(
                "document.querySelectorAll('video').forEach(function(video){video.pause()})",
                null,
            )
            web.visibility = View.INVISIBLE
            web.onPause()
            browserPaused = true
            AppLog.i("Browser", "youtube · picker browser rendering paused")

            // Keep JavaScript available until the browser has supplied its
            // optional proof. Timer suspension before that would block it.
            val proofReady = withTimeoutOrNull(4_000L) {
                while (YouTubeLocalProof.available() == null) delay(100)
                true
            } == true
            if (proofReady) {
                web.pauseTimers()
                timersPaused = true
            }
            AppLog.i("Browser", "youtube · picker browser timers paused=$timersPaused · proofReady=$proofReady")
        } else if (browserPaused) {
            if (timersPaused) {
                web.resumeTimers()
                timersPaused = false
            }
            web.onResume()
            web.visibility = View.VISIBLE
            browserPaused = false
            AppLog.i("Browser", "youtube · picker browser resumed")
        }
    }
    DisposableEffect(web) {
        onDispose {
            if (timersPaused) web.resumeTimers()
            if (browserPaused) web.onResume()
            web.stopLoading()
            web.removeJavascriptInterface("MediaLoaderBridge")
            web.webViewClient = WebViewClient()
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
    }
    BackHandler {
        if (web.canGoBack()) web.goBack() else onBack()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            // The system Back gesture navigates browser history; the toolbar icon always closes WV.
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.Close, contentDescription = "Закрыть браузер")
            }
            Text(source.title, style = MaterialTheme.typography.titleLarge)
        }
        if (progress in 0..99) LinearProgressIndicator(Modifier.fillMaxWidth())
        navigationError?.let { Text(it, modifier = Modifier.fillMaxWidth().padding(12.dp),
            color = MaterialTheme.colorScheme.error) }
        AndroidView(factory = { web }, modifier = Modifier.weight(1f).fillMaxWidth())
        if (source.acceptedLink(pageUrl) != null) {
            Button(
                onClick = {
                    source.acceptedLink(pageUrl)?.let { link ->
                        runCatching { syncSession(source, context.applicationContext) }
                        currentDownload(link)
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text("Скачать")
            }
        }
    }
}

private fun syncSession(source: BrowserSource, context: android.content.Context) {
    val manager = CookieManager.getInstance()
    manager.flush()
    when (source) {
        BrowserSource.INSTAGRAM -> {
            MediaRepository.get(context).sessions.syncFromWebView()
        }
        BrowserSource.YOUTUBE -> YoutubeDlAndroid(context).saveWebSession(
            manager.getCookie("https://www.youtube.com"), manager.getCookie("https://www.google.com"),
            manager.getCookie("https://accounts.google.com"))
        BrowserSource.VK -> VkYtDlpRuntime(context).captureWebSession(manager)
        BrowserSource.RUTUBE -> RutubeYtDlpRuntime(context).saveWebSession(
            manager.getCookie("https://rutube.ru"), manager.getCookie("https://www.rutube.ru"),
            manager.getCookie("https://studio.rutube.ru"))
    }
}

/** Compact in-page actions follow feed cards; the native action appears only on a media URL. */
private val browserScript = """
(function () {
  var source = '__SOURCE__';
  if (window.__mediaLoaderInstalled === source) return;
  window.__mediaLoaderInstalled = source;

  function valid(url) {
    try {
      var u = new URL(url, location.href), p = u.pathname;
      if (u.protocol !== 'https:') return false;
      if (source === 'instagram') return /(^|\.)instagram\.com$/.test(u.hostname) &&
        (/^\/(p|reel|tv)\/[A-Za-z0-9_-]{5,64}/.test(p) || /^\/stories\/[^/]+\/\d{5,25}/.test(p));
      if (source === 'youtube') return /(^|\.)youtube\.com$/.test(u.hostname) &&
        (/^\/(shorts|live)\/[A-Za-z0-9_-]{11}/.test(p) ||
          (p === '/watch' && /^[A-Za-z0-9_-]{11}$/.test(u.searchParams.get('v') || '')));
      if (source === 'vk') return /(^|\.)(vk\.com|vk\.ru|vkvideo\.ru)$/.test(u.hostname) &&
        (/^\/(video|clip)/.test(p) || /(^|[?&])z=(video|clip)/.test(u.search));
      return /(^|\.)rutube\.ru$/.test(u.hostname) &&
        (/^\/(live\/)?video\/(private\/)?[0-9a-fA-F]{32}\/?/.test(p) ||
          /^\/(play\/)?embed\/[0-9A-Za-z]+\/?$/.test(p));
    } catch (_) { return false; }
  }

  function send(url) {
    if (valid(url)) MediaLoaderBridge.pick(new URL(url, location.href).href);
  }

  function cardFor(anchor) {
    var selector = source === 'youtube'
      ? 'ytm-rich-item-renderer,ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-item-section-renderer ytm-video-card-renderer,ytd-rich-grid-media,ytd-video-renderer'
      : source === 'vk'
        ? '[data-post-id],.VideoCard,.video_item,.video_card'
        : '.video-card,.video-card-container,article';
    var card = anchor.closest(selector);
    if (card || source !== 'youtube') return card;
    // YouTube frequently changes the mobile renderer wrapper without changing
    // the actual watch link. Use the nearest bounded card with a thumbnail.
    var scan = anchor.parentElement;
    for (var depth = 0; scan && scan !== document.body && depth < 7; depth++, scan = scan.parentElement) {
      var rect = scan.getBoundingClientRect();
      if (rect.width < 140 || rect.width > innerWidth * 1.10) continue;
      if (rect.height < 70 || rect.height > 850) continue;
      if (scan.querySelector('img,ytm-thumbnail-cover,yt-image')) return scan;
    }
    return null;
  }

  var lastLocation = '';
  function report() {
    if (lastLocation === location.href) return;
    lastLocation = location.href;
    MediaLoaderBridge.location(lastLocation);
  }

  var recoveryUntil = Date.now() + 15000, recoveryPath = location.pathname;
  function recoverInstagramHome() {
    if (source !== 'instagram') return;
    if (recoveryPath !== location.pathname) {
      recoveryPath = location.pathname;
      recoveryUntil = Date.now() + 15000;
    }
    var postRoute = /^\/(?:[A-Za-z0-9._]+\/)?(?:p|reel|tv)\/[A-Za-z0-9_-]{5,64}\/?$/.test(location.pathname);
    if (!postRoute && !/^\/(?:[A-Za-z0-9._]+\/?)?$/.test(location.pathname)) return;
    if (Date.now() > recoveryUntil) return;
    var labels = Array.from(document.querySelectorAll('body *')).filter(function(el) {
      return el.children.length === 0 &&
        /^(Использовать приложение|Use the app|Open in app)$/i.test((el.textContent || '').trim());
    });
    labels.forEach(function(label) {
      var bar = label;
      for (var i = 0; i < 4 && bar.parentElement; i++) {
        bar = bar.parentElement;
        var rect = bar.getBoundingClientRect();
        if (rect.width > innerWidth * .7 && rect.height < 140 && rect.bottom > innerHeight * .7) {
          var close = Array.from(bar.querySelectorAll('button,[role="button"]')).find(function(el) {
            var name = (el.getAttribute('aria-label') || el.textContent || '').trim();
            return /^(×|✕|✖|x|close|закрыть|not now|не сейчас)$/i.test(name);
          });
          if (close) close.click();
          break;
        }
      }
    });
    var locked = [document.body, document.documentElement].some(function(el) {
      return ['hidden', 'clip'].indexOf(getComputedStyle(el).overflowY) >= 0;
    });
    function hasPostMedia(el) {
      return Array.from(el.querySelectorAll('img,video')).some(function(media) {
        var r = media.getBoundingClientRect();
        return r.width >= 150 && r.height >= 150 && r.bottom > 0 && r.top < innerHeight;
      });
    }
    // The post itself is often a dialog. A second Instagram prompt may be
    // mounted above it, so the existence of a visible dialog is insufficient.
    var dialogs = Array.from(document.querySelectorAll('[role="dialog"],[aria-modal="true"]'));
    var postMediaVisible = hasPostMedia(document.body);
    var emptyDialogs = dialogs.filter(function(el) {
      var r = el.getBoundingClientRect();
      return r.width > 50 && r.height > 50 && r.bottom > 0 && r.top < innerHeight &&
        (!postRoute || postMediaVisible) && !hasPostMedia(el);
    });
    var points = [[.5,.5],[.08,.12],[.92,.12],[.08,.88],[.92,.88]];
    var chain = [];
    points.forEach(function(point) {
      var top = document.elementFromPoint(innerWidth * point[0], innerHeight * point[1]);
      while (top && top !== document.body) {
        if (chain.indexOf(top) < 0) chain.push(top);
        top = top.parentElement;
      }
    });
    var covers = chain.filter(function(el) {
      var style = getComputedStyle(el), r = el.getBoundingClientRect();
      return (!postRoute || postMediaVisible) && style.position === 'fixed' && r.width >= innerWidth * .95 &&
        r.height >= innerHeight * .85 && style.backgroundColor !== 'rgba(0, 0, 0, 0)' &&
        !hasPostMedia(el) && !el.querySelector('main,nav,[data-medialoader-download]');
    });
    if (!locked && !covers.length && !emptyDialogs.length) return;
    var visible = dialogs.some(function(el) {
      var r = el.getBoundingClientRect();
      if (!(r.width > 50 && r.height > 50 && r.bottom > 0 && r.top < innerHeight)) return false;
      return Array.from(el.querySelectorAll('button,a,input,[role="button"]')).some(function(control) {
        var cr = control.getBoundingClientRect();
        return cr.width > 0 && cr.height > 0 && cr.bottom > 0 && cr.top < innerHeight;
      });
    });
    if (visible && !postRoute) return;
    if (visible && postRoute) {
      if (covers.length || emptyDialogs.length) MediaLoaderBridge.debug(
        'Instagram post backdrop removed · covers=' + covers.length + ' dialogs=' + emptyDialogs.length);
      emptyDialogs.forEach(function(el) { el.remove(); });
      covers.forEach(function(el) { el.remove(); });
      if (emptyDialogs.length || covers.length) {
        document.body.style.overflow = 'auto';
        document.documentElement.style.overflow = 'auto';
      }
      return;
    }
    if (dialogs.length || covers.length) MediaLoaderBridge.debug('Instagram empty backdrop removed');
    dialogs.forEach(function(el) { el.remove(); });
    covers.forEach(function(el) { el.remove(); });
    document.body.style.overflow = 'auto';
    document.documentElement.style.overflow = 'auto';
  }

  function buttonMarkup() {
    return '<svg width="23" height="23" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3v12m-4-4 4 4 4-4M4 19h16"/></svg>';
  }

  function mediaCandidates(root) {
    return Array.from((root || document).querySelectorAll('video,img')).filter(function(el) {
      var r = el.getBoundingClientRect(), style = getComputedStyle(el);
      return r.width >= Math.min(180, innerWidth * .48) && r.height >= 160 &&
        r.right > 0 && r.left < innerWidth &&
        style.display !== 'none' && style.visibility !== 'hidden' && Number(style.opacity || 1) > 0;
    });
  }

  function largestMedia(card) {
    var candidates = mediaCandidates(card);
    candidates.sort(function(a, b) {
      var ar = a.getBoundingClientRect(), br = b.getBoundingClientRect();
      return (br.width * br.height) - (ar.width * ar.height);
    });
    return candidates[0] || null;
  }

  function normalizedInstagramUrl(value) {
    if (!value || typeof value !== 'string') return null;
    var text = value.replace(/\\u002[fF]/g, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
    var match = text.match(/(?:https:\/\/(?:www\.)?instagram\.com)?\/(?:p|reel|tv)\/[A-Za-z0-9_-]{5,64}\/?/i);
    if (!match) return null;
    try {
      var url = new URL(match[0], 'https://www.instagram.com/').href;
      return valid(url) ? url : null;
    } catch (_) { return null; }
  }

  function instagramUrlFromAttributes(node) {
    if (!node || !node.getAttributeNames) return null;
    var names;
    try { names = node.getAttributeNames(); } catch (_) { names = []; }
    for (var i = 0; i < names.length; i++) {
      var raw = null;
      try { raw = node.getAttribute(names[i]); } catch (_) {}
      var found = normalizedInstagramUrl(raw);
      if (found) return found;
    }
    return null;
  }

  function instagramPermalink(card) {
    if (!card) return null;
    var links = card.querySelectorAll('a[href]');
    for (var i = 0; i < links.length; i++) {
      if (valid(links[i].href)) return new URL(links[i].href, location.href).href;
    }
    var nodes = card.querySelectorAll('*');
    for (var j = 0; j < nodes.length && j < 240; j++) {
      var fromAttrs = instagramUrlFromAttributes(nodes[j]);
      if (fromAttrs) return fromAttrs;
    }
    var selfAttrs = instagramUrlFromAttributes(card);
    if (selfAttrs) return selfAttrs;
    var html = '';
    try { html = card.outerHTML.slice(0, 420000); } catch (_) {}
    return normalizedInstagramUrl(html);
  }

  function instagramCardForMedia(media) {
    var article = media.closest('article');
    if (article) return article;
    var node = media.parentElement, best = null, mediaRect = media.getBoundingClientRect();
    for (var depth = 0; node && node !== document.body && depth < 16; depth++, node = node.parentElement) {
      var rect = node.getBoundingClientRect();
      if (rect.width < Math.min(230, innerWidth * .62) || rect.width > innerWidth * 1.18) continue;
      if (rect.height < mediaRect.height || rect.height > Math.max(innerHeight * 2.4, mediaRect.height + 950)) continue;
      if (!best) best = node;
      if (instagramPermalink(node)) return node;
      var controls = node.querySelectorAll('button,[role="button"],time,[role="link"]').length;
      if (controls >= 3 && rect.height >= mediaRect.height + 60) best = node;
    }
    return best;
  }

  function nearestInstagramPermalink(media, anchors) {
    var mr = media.getBoundingClientRect(), bestUrl = null, bestScore = Infinity;
    for (var i = 0; i < anchors.length; i++) {
      var a = anchors[i], ar = a.getBoundingClientRect();
      var vertical = ar.bottom < mr.top ? mr.top - ar.bottom : (ar.top > mr.bottom ? ar.top - mr.bottom : 0);
      if (vertical > 520) continue;
      var horizontal = ar.right < mr.left ? mr.left - ar.right : (ar.left > mr.right ? ar.left - mr.right : 0);
      if (horizontal > innerWidth * .45) continue;
      var score = vertical * 4 + horizontal +
        Math.abs((ar.left + ar.right) - (mr.left + mr.right)) * .15;
      if (score < bestScore) {
        bestScore = score;
        bestUrl = new URL(a.href, location.href).href;
      }
    }
    return bestUrl;
  }

  function positionInstagramButton(button) {
    var media = button.__mediaLoaderMedia;
    if (!media || !document.documentElement.contains(media)) {
      button.style.display = 'none';
      return false;
    }
    var r = media.getBoundingClientRect();
    var visible = r.width >= 180 && r.height >= 160 &&
      r.bottom > 0 && r.top < innerHeight && r.right > 0 && r.left < innerWidth;
    if (!visible) {
      button.style.display = 'none';
      return true;
    }
    button.style.left = Math.min(innerWidth - 48, Math.max(8, r.right - 48)) + 'px';
    button.style.top = Math.max(8, Math.min(innerHeight - 48, r.top + 8)) + 'px';
    button.style.display = 'inline-flex';
    return true;
  }

  function installInstagramOverlay(card, url, preferredMedia) {
    var media = preferredMedia || largestMedia(card);
    if (!media) return null;
    var existing = Array.from(document.querySelectorAll('[data-medialoader-overlay="1"]')).find(function(el) {
      return el.dataset.url === url;
    });
    var b = existing || document.createElement('button');
    if (!existing) {
      b.type = 'button';
      b.innerHTML = buttonMarkup();
      b.setAttribute('data-medialoader-overlay', '1');
      b.setAttribute('aria-label', 'Скачать этот материал');
      b.style.cssText = 'position:fixed;z-index:2147483646;width:40px;height:40px;padding:0;border:0;border-radius:12px;background:#7638e8;color:white;align-items:center;justify-content:center;box-shadow:0 2px 10px rgba(0,0,0,.35);cursor:pointer;-webkit-tap-highlight-color:transparent';
      b.addEventListener('click', function(e) {
        e.preventDefault();
        e.stopPropagation();
        e.stopImmediatePropagation();
        send(this.dataset.url);
      }, true);
      document.body.appendChild(b);
    }
    b.dataset.url = url;
    b.__mediaLoaderCard = card;
    b.__mediaLoaderMedia = media;
    b.__mediaLoaderSeen = true;
    positionInstagramButton(b);
    return b;
  }

  function reconcileInstagramOverlays() {
    Array.from(document.querySelectorAll('[data-medialoader-overlay="1"]')).forEach(function(b) {
      if (!b.__mediaLoaderSeen || !positionInstagramButton(b)) b.remove();
      else b.__mediaLoaderSeen = false;
    });
  }

  function installButton(card, url) {
    var existing = card.querySelector('[data-medialoader-download]');
    if (existing) {
      existing.dataset.url = url;
      return false;
    }
    var b = document.createElement('button');
    b.type = 'button';
    b.innerHTML = buttonMarkup();
    b.setAttribute('data-medialoader-download', '1');
    b.setAttribute('aria-label', 'Скачать этот материал');
    b.style.cssText = 'display:inline-flex;align-items:center;justify-content:center;vertical-align:middle;position:relative;z-index:2;width:40px;height:40px;max-width:100%;flex:0 0 40px;margin:6px;border:0;border-radius:12px;background:#7638e8;color:white;font:600 26px system-ui,sans-serif;line-height:1;box-sizing:border-box;cursor:pointer';
    b.addEventListener('click', function(e) {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      send(this.dataset.url);
    }, true);
    b.dataset.url = url;
    card.appendChild(b);
    return true;
  }

  var lastInstagramDebugAt = 0;
  function decorateInstagram() {
    document.querySelectorAll('[data-medialoader-overlay="1"]').forEach(function(b) {
      b.__mediaLoaderSeen = false;
    });
    var allAnchors = Array.from(document.querySelectorAll('a[href]'));
    var validAnchors = allAnchors.filter(function(a) { return valid(a.href); });
    var medias = mediaCandidates(document), cards = [], seenCards = [];

    for (var i = 0; i < medias.length && cards.length < 80; i++) {
      var card = instagramCardForMedia(medias[i]);
      if (!card || seenCards.indexOf(card) >= 0) continue;
      seenCards.push(card);
      cards.push({card: card, media: medias[i]});
    }

    var installed = 0, unresolved = 0;
    for (var j = 0; j < cards.length; j++) {
      var pair = cards[j], url = instagramPermalink(pair.card);
      if (!url) url = instagramUrlFromAttributes(pair.media);
      if (!url) url = nearestInstagramPermalink(pair.media, validAnchors);
      if (!url) {
        unresolved++;
        continue;
      }
      installInstagramOverlay(pair.card, url, pair.media);
      installed++;
    }

    for (var k = 0; k < validAnchors.length && installed < 120; k++) {
      var anchor = validAnchors[k], anchorCard = anchor.closest('article');
      if (!anchorCard) {
        var nearby = mediaCandidates(anchor.parentElement || document)[0];
        if (nearby) anchorCard = instagramCardForMedia(nearby);
      }
      if (!anchorCard) continue;
      installInstagramOverlay(anchorCard, new URL(anchor.href, location.href).href);
      installed++;
    }

    reconcileInstagramOverlays();

    if (Date.now() - lastInstagramDebugAt > 5000) {
      lastInstagramDebugAt = Date.now();
      MediaLoaderBridge.debug(
        'media=' + medias.length +
        ' cards=' + cards.length +
        ' anchors=' + validAnchors.length +
        ' unresolved=' + unresolved +
        ' overlays=' + document.querySelectorAll('[data-medialoader-overlay="1"]').length +
        ' path=' + location.pathname
      );
    }
  }

  function youtubeThumbnail(card) {
    if (!card) return null;
    // Mobile YouTube frequently uses custom thumbnail elements and CSS
    // background images, not plain <img> tags.
    var selector = 'img,yt-image,ytm-thumbnail,ytm-thumbnail-cover,' +
      'ytm-video-thumbnail-container,[class*="thumbnail"],' +
      '[style*="background-image"]';
    var candidates = Array.from(card.querySelectorAll(selector));
    if (card.matches && card.matches(selector)) candidates.unshift(card);
    var best = null, bestArea = 0;
    for (var i = 0; i < candidates.length; i++) {
      var rect = candidates[i].getBoundingClientRect();
      var area = rect.width * rect.height;
      // Ignore avatars/icons and oversized feed containers.
      if (rect.width < 130 || rect.height < 80 || area < 14000) continue;
      if (rect.width > innerWidth * 1.04 || rect.height > 750) continue;
      if (area > bestArea) { bestArea = area; best = candidates[i]; }
    }
    // Sometimes a <a href="/watch"> is itself the thumbnail surface.
    if (!best && card.matches && card.matches('a[href]') && valid(card.href)) {
      var frame = card.getBoundingClientRect();
      if (frame.width >= 145 && frame.height >= 85 &&
          frame.width <= innerWidth * 1.04 && frame.height <= 700) return card;
    }
    return best;
  }

  function youtubeVideoUrl(card) {
    if (!card) return null;
    var anchor = card.matches && card.matches('a[href]') ? card : null;
    var links = anchor ? [anchor] : Array.from(card.querySelectorAll('a[href]'));
    for (var i = 0; i < links.length; i++) {
      if (valid(links[i].href)) return new URL(links[i].href, location.href).href;
    }
    var holder = card;
    for (var j = 0; j < 4 && holder; j++, holder = holder.parentElement) {
      var videoId = holder.getAttribute('video-id') || holder.getAttribute('data-video-id');
      if (videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId))
        return 'https://www.youtube.com/watch?v=' + videoId;
    }
    var dataId = youtubeIdFromRendererData(card);
    if (dataId) return 'https://www.youtube.com/watch?v=' + dataId;
    return null;
  }

  function youtubeIdFromRendererData(card) {
    // Current mobile renderers may keep the watch endpoint in their JS model
    // while exposing neither an anchor nor a thumbnail URL in the DOM.
    var roots = [card.data, card.__data, card.model, card.viewModel,
      card.polymerController && card.polymerController.data];
    var descendants = card.querySelectorAll ? card.querySelectorAll('*') : [];
    for (var d = 0; d < descendants.length && d < 40; d++) {
      var child = descendants[d];
      if (child.data) roots.push(child.data);
      if (child.viewModel) roots.push(child.viewModel);
    }
    var queue = roots.map(function(value) { return [value, 0]; });
    var seen = new Set(), visited = 0;
    while (queue.length && visited < 180) {
      var entry = queue.shift(), value = entry[0], depth = entry[1];
      if (!value || typeof value !== 'object' || seen.has(value) || value.nodeType) continue;
      seen.add(value); visited++;
      var id = value.videoId || value.video_id || value.contentId;
      if (typeof id === 'string' && /^[A-Za-z0-9_-]{11}$/.test(id)) return id;
      if (depth >= 6) continue;
      var keys = Object.keys(value);
      for (var k = 0; k < keys.length && k < 48; k++) {
        var next;
        try { next = value[keys[k]]; } catch (_) { continue; }
        if (next && typeof next === 'object') queue.push([next, depth + 1]);
      }
    }
    return null;
  }

  function youtubeIdFromThumbnail(thumb) {
    if (!thumb) return null;
    var sources = [thumb.currentSrc, thumb.src, thumb.getAttribute('src'),
      thumb.getAttribute('data-src'), getComputedStyle(thumb).backgroundImage];
    for (var i = 0; i < sources.length; i++) {
      var value = sources[i] || '';
      if (typeof value !== 'string') continue;
      var match = value.match(/https?:\/\/(?:[a-z0-9-]+\.)?(?:ytimg\.com|img\.youtube\.com)\/(?:vi|vi_webp)\/([A-Za-z0-9_-]{11})(?:\/|\b)/i);
      if (match) return match[1];
    }
    return null;
  }

  var youtubeFeedRoot = null, youtubeFeedTitleCache = [];
  function youtubeFeedTitles() {
    // YouTube's current mobile feed sometimes renders cards without watch
    // anchors, video-id attributes or ytimg URLs. Its bootstrap response still
    // contains the exact title and video ID. Only use unique title matches.
    var root = window.ytInitialData;
    if (!root || typeof root !== 'object') return [];
    if (root === youtubeFeedRoot) return youtubeFeedTitleCache;
    var queue = [root], cursor = 0, seen = new Set(), titles = new Map(), scanned = 0;
    while (cursor < queue.length && scanned < 8000) {
      var item = queue[cursor++];
      if (!item || typeof item !== 'object' || seen.has(item)) continue;
      seen.add(item); scanned++;
      var id = item.videoId;
      var title = item.title && (item.title.simpleText ||
        (Array.isArray(item.title.runs) && item.title.runs.map(function(run) {
          return run && typeof run.text === 'string' ? run.text : '';
        }).join('')));
      if (typeof id === 'string' && /^[A-Za-z0-9_-]{11}$/.test(id) &&
          typeof title === 'string' && title.trim().length >= 8) {
        var key = title.trim().replace(/\s+/g, ' ').toLowerCase();
        if (titles.has(key) && titles.get(key) !== id) titles.set(key, null);
        else if (!titles.has(key)) titles.set(key, id);
      }
      var itemKeys;
      try { itemKeys = Object.keys(item); } catch (_) { continue; }
      itemKeys.slice(0, 80).forEach(function(key) {
        var value;
        try { value = item[key]; } catch (_) { return; }
        if (value && typeof value === 'object') {
          if (Array.isArray(value)) value.slice(0, 250).forEach(function(v) { queue.push(v); });
          else queue.push(value);
        }
      });
    }
    youtubeFeedRoot = root;
    youtubeFeedTitleCache = Array.from(titles).filter(function(pair) { return pair[1]; });
    return youtubeFeedTitleCache;
  }

  function youtubeIdFromFeedTitle(thumb, titles) {
    var node = thumb;
    for (var depth = 0; node && node !== document.body && depth < 7; depth++, node = node.parentElement) {
      var rect = node.getBoundingClientRect();
      if (rect.width < 130 || rect.width > innerWidth * 1.1 || rect.height > 750) continue;
      var text = (node.innerText || '').trim().replace(/\s+/g, ' ').toLowerCase();
      if (!text || text.length > 500) continue;
      var matches = titles.filter(function(pair) { return text.indexOf(pair[0]) >= 0; });
      if (matches.length === 1) return matches[0][1];
    }
    return null;
  }

  function positionYoutubeOverlay(b) {
    var thumb = b.__mediaLoaderThumb;
    if (!thumb || !document.documentElement.contains(thumb)) return false;
    var rect = thumb.getBoundingClientRect();
    if (rect.width < 100 || rect.height < 80 ||
        rect.bottom <= 0 || rect.top >= innerHeight ||
        rect.right <= 0 || rect.left >= innerWidth) {
      b.style.display = 'none';
      return true;
    }
    // Same unobtrusive 40px icon as Instagram/RUTUBE, pinned to thumbnail edge.
    b.style.left = Math.max(6, Math.min(innerWidth - 46, rect.right - 47)) + 'px';
    b.style.top = Math.max(6, Math.min(innerHeight - 46, rect.bottom - 47)) + 'px';
    b.style.display = 'inline-flex';
    return true;
  }

  function youtubeDownloadIcon() {
    // YouTube enforces Trusted Types, so building SVG through innerHTML aborts
    // the entire card scan. DOM nodes work without relaxing the page policy.
    var ns = 'http://www.w3.org/2000/svg';
    var svg = document.createElementNS(ns, 'svg');
    [['width', '23'], ['height', '23'], ['viewBox', '0 0 24 24'],
      ['fill', 'none'], ['stroke', 'currentColor'], ['stroke-width', '2.2'],
      ['stroke-linecap', 'round'], ['stroke-linejoin', 'round'],
      ['aria-hidden', 'true']].forEach(function(pair) {
        svg.setAttribute(pair[0], pair[1]);
      });
    var path = document.createElementNS(ns, 'path');
    path.setAttribute('d', 'M12 3v12m-4-4 4 4 4-4M4 19h16');
    svg.appendChild(path);
    return svg;
  }

  function installYoutubeOverlay(thumb, url) {
    if (!thumb || !valid(url)) return;
    var buttons = document.querySelectorAll('[data-medialoader-youtube="1"]');
    var b = Array.from(buttons).find(function(el) { return el.__mediaLoaderThumb === thumb; });
    if (!b) {
      b = document.createElement('button');
      b.type = 'button';
      b.appendChild(youtubeDownloadIcon());
      b.setAttribute('data-medialoader-youtube', '1');
      b.setAttribute('aria-label', 'Скачать видео');
      b.style.cssText = 'position:fixed;z-index:2147483646;width:40px;height:40px;border:0;' +
        'border-radius:12px;background:#7638e8;color:white;display:inline-flex;' +
        'align-items:center;justify-content:center;box-shadow:0 2px 10px rgba(0,0,0,.35);' +
        'cursor:pointer;-webkit-tap-highlight-color:transparent';
      b.addEventListener('click', function(e) {
        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
        send(this.dataset.url);
      }, true);
      document.body.appendChild(b);
    }
    b.__mediaLoaderSeen = true;
    b.__mediaLoaderThumb = thumb;
    b.dataset.url = url;
    positionYoutubeOverlay(b);
  }

  var lastYoutubeDebugAt = 0;
  function decorateYoutube() {
    var previous = document.querySelectorAll('[data-medialoader-youtube="1"]');
    previous.forEach(function(b) { b.__mediaLoaderSeen = false; });
    var anchors = Array.from(document.querySelectorAll('a[href]'));
    var validLinks = anchors.filter(function(a) { return valid(a.href); });
    var usable = 0;
    for (var i = 0; i < validLinks.length && i < 260; i++) {
      var a = validLinks[i];
      var card = cardFor(a) || a.parentElement;
      var thumb = youtubeThumbnail(card) || youtubeThumbnail(a);
      if (!thumb && a.parentElement) thumb = youtubeThumbnail(a.parentElement);
      // The text link and the thumbnail may belong to sibling containers.
      if (!thumb) {
        var wrapper = a;
        for (var depth = 0; !thumb && depth < 5 && wrapper && wrapper.parentElement; depth++) {
          wrapper = wrapper.parentElement;
          if (wrapper === document.body || wrapper.getBoundingClientRect().height > 800) break;
          thumb = youtubeThumbnail(wrapper);
        }
      }
      if (thumb) {
        installYoutubeOverlay(thumb, new URL(a.href, location.href).href);
        usable++;
      }
    }
    // New YouTube mobile renderers can expose the ID without a normal anchor.
    var renderers = document.querySelectorAll(
      'ytm-rich-item-renderer,ytm-compact-video-renderer,ytm-video-with-context-renderer,' +
      'ytm-video-card-renderer,ytm-shorts-lockup-view-model,yt-lockup-view-model,' +
      'ytm-grid-video-renderer,ytd-rich-grid-media,ytd-video-renderer,[video-id],[data-video-id]');
    var renderersWithData = 0;
    for (var j = 0; j < renderers.length && j < 180; j++) {
      if (renderers[j].data || renderers[j].__data || renderers[j].viewModel)
        renderersWithData++;
      var url;
      try { url = youtubeVideoUrl(renderers[j]); } catch (_) { continue; }
      if (!url) continue;
      var thumbnail = youtubeThumbnail(renderers[j]);
      if (!thumbnail) continue;
      installYoutubeOverlay(thumbnail, url);
      usable++;
    }
    // The mobile feed can render clickable cards without an <a> or known
    // renderer. A YouTube-owned thumbnail URL still carries the exact video ID.
    var thumbnailNodes = document.querySelectorAll('img,yt-image,ytm-thumbnail-cover,[style*="background-image"]');
    var thumbnailIds = 0;
    var feedTitles = youtubeFeedTitles(), titleIds = 0;
    for (var k = 0; k < thumbnailNodes.length && k < 260; k++) {
      var node = thumbnailNodes[k], id;
      try { id = youtubeIdFromThumbnail(node); } catch (_) { continue; }
      if (!id && feedTitles.length) {
        id = youtubeIdFromFeedTitle(node, feedTitles);
        if (id) titleIds++;
      }
      if (!id) continue;
      var surface = youtubeThumbnail(node.parentElement) || node;
      var rect = surface.getBoundingClientRect();
      if (rect.width < 130 || rect.height < 80 || rect.width > innerWidth * 1.04) continue;
      installYoutubeOverlay(surface, 'https://www.youtube.com/watch?v=' + id);
      thumbnailIds++;
    }
    previous.forEach(function(b) {
      if (!b.__mediaLoaderSeen || !positionYoutubeOverlay(b)) b.remove();
    });
    if (Date.now() - lastYoutubeDebugAt > 5500) {
      lastYoutubeDebugAt = Date.now();
      MediaLoaderBridge.debug('YouTube cards: anchors=' + anchors.length +
        ' valid=' + validLinks.length +
        ' thumbs=' + document.querySelectorAll('img,yt-image,ytm-thumbnail-cover,[class*="thumbnail"]').length +
        ' renderers=' + renderers.length + ' rendererData=' + renderersWithData +
        ' matched=' + usable + ' thumbnailIds=' + thumbnailIds +
        ' titleIds=' + titleIds + ' feedTitles=' + feedTitles.length +
        ' buttons=' + document.querySelectorAll('[data-medialoader-youtube="1"]').length +
        ' page=' + location.pathname);
    }
  }

  function decorate() {
    report();
    recoverInstagramHome();

    if (source === 'instagram') {
      decorateInstagram();
      return;
    }
    if (source === 'youtube') {
      decorateYoutube();
      return;
    }

    var anchors = document.querySelectorAll('a[href]'), count = 0;
    for (var i = 0; i < anchors.length && count < 240; i++) {
      var a = anchors[i];
      if (!valid(a.href)) continue;
      var card = cardFor(a);
      if (!card) continue;
      installButton(card, new URL(a.href, location.href).href);
      count++;
    }
    if (source === 'youtube') {
      // The current mobile site sometimes nests its watch link in non-anchor
      // view-model wrappers; discover only exact 11-character video IDs or
      // valid watch/short links. No guessed URLs or automatic opening.
      var renderers = document.querySelectorAll(
        'ytm-rich-item-renderer,ytm-compact-video-renderer,' +
        'ytm-video-with-context-renderer,ytm-video-card-renderer,' +
        'ytm-shorts-lockup-view-model,ytm-grid-video-renderer,' +
        'yt-lockup-view-model,ytd-rich-grid-media,ytd-video-renderer'
      );
      for (var j = 0; j < renderers.length && j < 160; j++) {
        var cardNode = renderers[j];
        if (cardNode.querySelector('[data-medialoader-download]')) continue;
        var link = cardNode.querySelector('a[href*="/watch"],a[href*="/shorts/"],a[href*="/live/"]');
        var mediaLink = link && valid(link.href) ? link.href : null;
        if (!mediaLink) {
          var videoId = cardNode.getAttribute('video-id') ||
              cardNode.getAttribute('data-video-id') ||
              (cardNode.querySelector('[video-id],[data-video-id]') || {}).getAttribute?.('video-id') ||
              (cardNode.querySelector('[data-video-id]') || {}).getAttribute?.('data-video-id');
          if (videoId && /^[A-Za-z0-9_-]{11}$/.test(videoId)) {
            mediaLink = 'https://www.youtube.com/watch?v=' + videoId;
          }
        }
        if (mediaLink && valid(mediaLink)) installButton(cardNode, mediaLink);
      }
    }
  }

  var timer;
  var lastYoutubeErrorAt = 0;
  function safelyDecorate() {
    try { decorate(); }
    catch (error) {
      if (source === 'youtube' && Date.now() - lastYoutubeErrorAt > 5000) {
        lastYoutubeErrorAt = Date.now();
        var reason = error && error.message ? String(error.message) : 'unknown';
        MediaLoaderBridge.debug('YouTube card scan error: ' +
          (error && error.name ? error.name : 'unknown') + ' · ' + reason.slice(0, 100));
      }
    }
  }
  function scheduleDecorate(delay) {
    clearTimeout(timer);
    timer = setTimeout(safelyDecorate, delay || 180);
  }

  new MutationObserver(function(records) {
    if (records.every(function(r) {
      return r.type === 'childList' && r.addedNodes.length &&
        Array.from(r.addedNodes).every(function(n) {
          return n.nodeType === 1 && n.hasAttribute &&
            (n.hasAttribute('data-medialoader-download') || n.hasAttribute('data-medialoader-overlay'));
        });
    })) return;
    scheduleDecorate(180);
  }).observe(document.documentElement, {
    childList: true,
    subtree: true,
    attributes: source === 'instagram',
    attributeFilter: source === 'instagram' ? ['href'] : undefined
  });

  window.addEventListener('popstate', report);
  window.addEventListener('hashchange', report);
  safelyDecorate();

  if (source === 'youtube') {
    // Feasibility probe only. The known WebPoClient path comes from the
    // experimental browser provider. Never send token or binding to the bridge.
    setTimeout(function() {
      try {
        var flags = window.ytcfg && typeof window.ytcfg.get === 'function'
          ? window.ytcfg.get('EXPERIMENT_FLAGS') : null;
        var client = window.top['havuokmhhs-0']?.bevasrs?.wpc;
        MediaLoaderBridge.debug('YouTube local token probe: experiment=' +
          !!(flags && flags.bg_st_hr) + ' knownClient=' + (typeof client === 'function'));
        var binding = window.ytcfg && typeof window.ytcfg.get === 'function'
          ? window.ytcfg.get('VISITOR_DATA') : null;
        if (typeof client !== 'function' || typeof binding !== 'string' || !binding) {
          MediaLoaderBridge.debug('YouTube local token mint: unavailable');
          return;
        }
        var attempts = 0;
        function tryMint() {
          attempts++;
          Promise.resolve().then(function() { return client(); })
            .then(function(instance) { return instance.mws({c: binding, mc: false, me: false}); })
            .then(function(token) {
              var available = typeof token === 'string' && token.length > 10;
              MediaLoaderBridge.debug('YouTube local token mint: ' +
                (available ? 'available' : 'empty'));
              if (available && window.top === window) {
                window.__mediaLoaderProof = {visitorData: binding, token: token};
                MediaLoaderBridge.localProofReady();
              }
            })
            .catch(function(error) {
              if (String(error).includes('SDF:notready') && attempts < 5) {
                setTimeout(tryMint, 1500);
              } else {
                MediaLoaderBridge.debug('YouTube local token mint: failed');
              }
            });
        }
        tryMint();
      } catch (_) {
        MediaLoaderBridge.debug('YouTube local token probe: unavailable');
      }
    }, 12000);
    var youtubeFrame = 0;
    function repositionYoutubeOverlays() {
      if (youtubeFrame) return;
      youtubeFrame = requestAnimationFrame(function() {
        youtubeFrame = 0;
        document.querySelectorAll('[data-medialoader-youtube="1"]').forEach(function(b) {
          positionYoutubeOverlay(b);
        });
      });
    }
    window.addEventListener('scroll', repositionYoutubeOverlays, true);
    window.addEventListener('resize', repositionYoutubeOverlays);
    // Re-evaluate SPAs whose scrolling does not trigger document mutations.
    setInterval(function() {
      if (document.documentElement.contains(document.body)) safelyDecorate();
    }, 1300);
  }

  if (source === 'instagram') {
    var overlayFrame = 0;
    function scheduleOverlayPosition() {
      if (overlayFrame) return;
      overlayFrame = requestAnimationFrame(function() {
        overlayFrame = 0;
        document.querySelectorAll('[data-medialoader-overlay="1"]').forEach(function(b) {
          positionInstagramButton(b);
        });
      });
    }
    window.addEventListener('scroll', scheduleOverlayPosition, true);
    window.addEventListener('resize', scheduleOverlayPosition);

    var feedReconcile = setInterval(function() {
      if (!document.documentElement.contains(document.body)) {
        clearInterval(feedReconcile);
        return;
      }
      decorate();
    }, 900);

    var recovery = setInterval(function() {
      if (!document.documentElement.contains(document.body)) clearInterval(recovery);
      else recoverInstagramHome();
    }, 900);
  }
})();
""".trimIndent()
