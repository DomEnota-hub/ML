package com.domenota.medialoader.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.view.ViewGroup
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.domenota.medialoader.data.MediaRepository
import com.domenota.medialoader.core.diagnostics.InstagramLoginFailure
import com.domenota.medialoader.core.logging.AppLog

/** Web login on Instagram's own page. Only session cookies are retained; credentials are never read. */
class LoginActivity : ComponentActivity() {
    private var web: WebView? = null
    private var errorPanel: View? = null
    private var errorMessage: TextView? = null
    private var lastHttpError = false
    private var finished = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollSession = object : Runnable {
        override fun run() {
            if (!finished && !isFinishing) {
                checkSession()
                handler.postDelayed(this, 1000)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.domenota.medialoader.ui.theme.edgeToEdgeWindow(window, dark = isDarkTheme())
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        AppLog.init(applicationContext)
        val root = FrameLayout(this)
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
            override fun onProgressChanged(view: WebView, newProgress: Int) { checkSession() }
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return !isAllowedHost(request.url.scheme, request.url.host)
                val uri = request.url
                if (isAllowedHost(uri.scheme, uri.host)) return false
                if (uri.scheme == "intent" || uri.scheme == "instagram") checkSession()
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                lastHttpError = false
                errorPanel?.visibility = View.GONE
                checkSession()
            }
            override fun onPageFinished(view: WebView, url: String?) { checkSession() }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest,
                response: WebResourceResponse) {
                if (!request.isForMainFrame || !isAllowedHost(request.url.scheme, request.url.host)) return
                lastHttpError = true
                AppLog.w("InstagramLogin", "Login page HTTP ${response.statusCode} · host=${request.url.host}")
                showPageError(InstagramLoginFailure.explain(response.statusCode))
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest,
                error: WebResourceError) {
                if (!request.isForMainFrame) return
                AppLog.w("InstagramLogin", "Login page network error · code=${error.errorCode}")
                if (lastHttpError) return
                showPageError("Не удалось открыть Instagram (ошибка сети ${error.errorCode}). Проверьте соединение и попробуйте ещё раз.")
            }
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 24)
            setBackgroundColor(if (isDarkTheme()) Color.rgb(18, 18, 24) else Color.WHITE)
            visibility = View.GONE
        }
        errorMessage = TextView(this).apply {
            textSize = 18f
            setTextColor(if (isDarkTheme()) Color.WHITE else Color.BLACK)
            panel.addView(this)
        }
        panel.addView(Button(this).apply {
            text = "Повторить"
            setOnClickListener { view.loadUrl(LOGIN_URL) }
        })
        panel.addView(Button(this).apply {
            text = "Открыть главную Instagram"
            setOnClickListener { view.loadUrl("https://www.instagram.com/") }
        })
        panel.addView(Button(this).apply {
            text = "Вернуться к настройкам"
            setOnClickListener { finish() }
        })
        panel.addView(TextView(this).apply {
            text = "Если вход остаётся недоступен, в настройках есть раздел «Резервная авторизация». Не отправляйте никому свой sessionid."
            setTextColor(if (isDarkTheme()) Color.LTGRAY else Color.DKGRAY)
        })
        errorPanel = panel
        root.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        ViewCompat.setOnApplyWindowInsetsListener(root) { target, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(root)
        handler.post(pollSession)
        view.loadUrl(LOGIN_URL)
    }

    private fun showPageError(message: String) {
        errorMessage?.text = message
        errorPanel?.visibility = View.VISIBLE
    }

    private fun isDarkTheme() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
        Configuration.UI_MODE_NIGHT_YES

    private fun checkSession(): Boolean {
        if (finished) return true
        val manager = CookieManager.getInstance()
        val session = MediaRepository.get(applicationContext).sessions
        if (!session.syncFromWebView()) return false
        finished = true
        manager.flush()
        AppLog.i("InstagramLogin", "Browser session cookie captured")
        setResult(RESULT_OK)
        finish()
        return true
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
        const val LOGIN_URL = "https://www.instagram.com/accounts/login/"

        fun isAllowedHost(scheme: String?, host: String?): Boolean {
            if (scheme != "https" || host == null) return false
            val name = host.lowercase()
            return name == "instagram.com" || name.endsWith(".instagram.com") ||
                name == "facebook.com" || name.endsWith(".facebook.com")
        }
    }
}
