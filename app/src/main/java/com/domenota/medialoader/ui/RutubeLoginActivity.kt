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
import com.domenota.medialoader.core.diagnostics.AccountBrowserPolicy
import com.domenota.medialoader.data.rutube.RutubeYtDlpRuntime

/**
 * RUTUBE web sign-in. Login, password and confirmation codes stay inside RUTUBE pages.
 * MediaLoader only keeps the completed browser session in app-private storage for yt-dlp.
 */
class RutubeLoginActivity : ComponentActivity() {
    private var web: WebView? = null
    private var finished = false
    private var verifying = false
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
        AppLog.i("RutubeAuth", "Web sign-in opened")

        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val hint = TextView(this).apply {
            text = "Войди в RUTUBE. Логин, пароль и код подтверждения приложение не читает и не сохраняет — используется только готовая веб-сессия."
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
                if (!request.isForMainFrame) return !isAllowedHost(request.url.scheme, request.url.host)
                return !isAllowedHost(request.url.scheme, request.url.host)
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
        val manager = CookieManager.getInstance()
        val rutube = manager.getCookie("https://rutube.ru")
        val www = manager.getCookie("https://www.rutube.ru")
        val studio = manager.getCookie("https://studio.rutube.ru")
        if (verifying || listOf(rutube, www, studio).all { it.isNullOrBlank() }) return false
        verifying = true
        web?.evaluateJavascript(AccountBrowserPolicy.rutubeProfileScript) { evidence ->
            verifying = false
            if (AccountBrowserPolicy.rutubeProfileConfirmed(evidence) && !isFinishing &&
                RutubeYtDlpRuntime(applicationContext).saveWebSession(rutube, www, studio)) {
                finished = true
                manager.flush()
                AppLog.i("RutubeAuth", "Browser profile visible · session captured")
                setResult(RESULT_OK)
                finish()
            }
        }
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(pollSession)
        if (!finished) RutubeYtDlpRuntime(applicationContext).discardUnverifiedSession()
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
        const val START_URL = "https://rutube.ru/profile/"

        fun isAllowedHost(scheme: String?, host: String?): Boolean {
            if (scheme != "https" || host == null) return false
            return AccountBrowserPolicy.allowsRutubeNavigation("$scheme://$host/")
        }
    }
}
