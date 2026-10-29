package com.domenota.medialoader.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.vk.VkYtDlpRuntime
import com.domenota.medialoader.ui.screens.VkAuthNavigationRecovery
import com.domenota.medialoader.ui.screens.VkAuthIntentLauncher

/** VK web sign-in. Credentials stay inside VK pages; only the resulting session cookies are saved. */
class VkLoginActivity : ComponentActivity() {
    private var web: WebView? = null
    private var finished = false
    private var awaitingVkApp = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollSession = object : Runnable {
        override fun run() {
            if (!finished && !isFinishing) {
                checkSession()
                handler.postDelayed(this, 1200L)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.domenota.medialoader.ui.theme.edgeToEdgeWindow(window)
        AppLog.i("VKAuth", "Web sign-in opened")

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply {
            text = "Войдите в VK. Логин и пароль приложение не читает и не сохраняет — сохраняется только готовая сессия для доступа к видео."
            textSize = 14f
            setPadding(32, 24, 32, 24)
        }
        root.addView(
            hint,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        val view = WebView(this)
        web = view
        val mobileUserAgent = view.settings.userAgentString
            .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
        view.settings.apply {
            userAgentString = mobileUserAgent
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        cookies.setAcceptThirdPartyCookies(view, true)
        view.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress > 35) checkSession()
            }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val blocked = !isAllowedHost(request.url.scheme, request.url.host)
                if (request.url.scheme.equals("intent", true))
                    AppLog.i("VKAuth", "VK QR handoff · " +
                        VkAuthNavigationRecovery.safeIntentSummary(request.url.toString()))
                val appTarget = if (blocked)
                    VkAuthNavigationRecovery.officialAppTarget(request.url.toString()) else null
                val recovery = if (blocked && appTarget == null)
                    VkAuthNavigationRecovery.secureTarget(request.url.toString()) else null
                if (recovery != null) {
                    AppLog.i("VKAuth", "VK QR-auth navigation upgraded to HTTPS")
                    view.loadUrl(recovery)
                    return true
                }
                if (appTarget != null) {
                    val opened = VkAuthIntentLauncher.open(this@VkLoginActivity, appTarget)
                    AppLog.i("VKAuth", "VK QR handoff · officialAppOpened=$opened")
                    if (opened) awaitingVkApp = true
                    else hint.text = "Не удалось открыть приложение VK. Вернитесь к форме входа и выберите телефон или почту."
                    return true
                }
                if (blocked) AppLog.w("VKAuth", "Main-frame navigation blocked · host=${request.url.host.orEmpty()} · scheme=${when(request.url.scheme) {
                    "http" -> "http"
                    "intent" -> "intent"
                    "https" -> "https"
                    else -> "other"
                }}")
                if (blocked) hint.text = "VK запросил переход, который встроенный браузер не смог открыть. Вернитесь назад и повторите вход."
                return blocked
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                checkSession()
            }

            override fun onPageFinished(view: WebView, url: String?) {
                checkSession()
            }
        }
        root.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        handler.post(pollSession)
        view.loadUrl(START_URL)
    }

    private fun checkSession(): Boolean {
        if (finished) return true
        val page = android.net.Uri.parse(web?.url ?: return false)
        val host = page.host?.lowercase() ?: return false
        // A stale cookie can exist before the first login page loads. Wait for
        // VK to return to an account page before capturing a new browser session.
        if (host !in setOf("vk.com", "m.vk.com", "vk.ru", "m.vk.ru") ||
            page.path.orEmpty().contains("login", ignoreCase = true)) return false
        val manager = CookieManager.getInstance()
        val saved = VkYtDlpRuntime(applicationContext).captureWebSession(manager)
        if (!saved) return false

        finished = true
        manager.flush()
        AppLog.i("VKAuth", "Authenticated web session captured")
        setResult(RESULT_OK)
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        if (awaitingVkApp && !finished) {
            awaitingVkApp = false
            AppLog.i("VKAuth", "VK app returned · login page preserved")
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollSession)
        web?.let { view ->
            view.stopLoading()
            view.webViewClient = WebViewClient()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        web = null
        super.onDestroy()
    }

    private companion object {
        const val START_URL = "https://vk.com/login"

        fun isAllowedHost(scheme: String?, host: String?): Boolean {
            if (scheme != "https" || host == null) return false
            val name = host.lowercase()
            return name == "vk.com" || name.endsWith(".vk.com") ||
                name == "vkvideo.ru" || name.endsWith(".vkvideo.ru") ||
                name == "vk.ru" || name.endsWith(".vk.ru") ||
                name == "vkid.ru" || name.endsWith(".vkid.ru")
        }
    }
}
