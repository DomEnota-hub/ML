package com.domenota.medialoader.data.diagnostics

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.domenota.medialoader.core.logging.AppLog
import com.domenota.medialoader.data.download.MuzmoDownloadUrls
import com.domenota.medialoader.ui.components.MuzmoDomProbePolicy
import com.domenota.medialoader.ui.components.MuzmoPageScripts
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener

/** Runs the same browser search script as the Muzmo UI, without selecting a track. */
internal class MuzmoDiagnosticSearch(private val context: Context) {
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun search(artist: String, title: String): Int = withContext(Dispatchers.Main.immediate) {
        val query = listOf(artist, title).filter(String::isNotBlank).joinToString(" ")
        require(query.isNotBlank())
        val address = MuzmoDownloadUrls.searchUrl(query)
        val pageReady = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Int>()
        var callbacks = 0
        val browser = WebView(context)
        try {
            browser.settings.javaScriptEnabled = true
            browser.settings.userAgentString = browser.settings.userAgentString
                .replace(Regex(";\\s*wv(?=[;)])", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Version/4\\.0(?=\\s|$)", RegexOption.IGNORE_CASE), "")
            browser.settings.domStorageEnabled = true
            browser.settings.allowFileAccess = false
            browser.settings.allowContentAccess = false
            browser.settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            browser.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView,
                    request: WebResourceRequest): Boolean = request.isForMainFrame &&
                    !MuzmoDownloadUrls.isSearchPage(request.url.toString())

                override fun onReceivedError(view: WebView, request: WebResourceRequest,
                    error: WebResourceError) {
                    if (request.isForMainFrame) {
                        if (!pageReady.isCompleted)
                            pageReady.completeExceptionally(IOException("Search page unavailable"))
                        if (!result.isCompleted)
                            result.completeExceptionally(IOException("Search page unavailable"))
                    }
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (!MuzmoDownloadUrls.isSearchPage(url.orEmpty()) || result.isCompleted) return
                    if (!pageReady.isCompleted) pageReady.complete(Unit)
                    AppLog.i("Diagnostics", "Reserve music probe · stage=page-ready")
                    var settledEmptyScans = 0
                    fun scan(attempt: Int) {
                        if (result.isCompleted) return
                        view.evaluateJavascript(MuzmoPageScripts.SEARCH) { raw ->
                            if (result.isCompleted) return@evaluateJavascript
                            callbacks++
                            val data = parseData(raw)
                            val count = data?.optJSONArray("tracks")?.length()
                            if (count == null) {
                                result.completeExceptionally(MuzmoDiagnosticTimeout("SCRIPT_PARSE"))
                                return@evaluateJavascript
                            }
                            val ready = data?.optString("readyState").orEmpty()
                            val links = data?.optInt("links", 0) ?: 0
                            settledEmptyScans = if (ready == "complete") settledEmptyScans + 1 else 0
                            when {
                                count > 0 || data.optBoolean("explicitEmpty", false) -> result.complete(count)
                                MuzmoDomProbePolicy.exhausted(ready, settledEmptyScans, links > 0) ->
                                    result.completeExceptionally(MuzmoDiagnosticTimeout("DOM_STRUCTURE"))
                                else -> view.postDelayed({ scan(attempt + 1) }, 1_200)
                            }
                        }
                    }
                    scan(1)
                }
            }
            browser.loadUrl(address)
            if (withTimeoutOrNull(12_000) { pageReady.await() } == null)
                throw MuzmoDiagnosticTimeout("PAGE")
            (withTimeoutOrNull(16_000) { result.await() }
                ?: throw MuzmoDiagnosticTimeout(if (callbacks == 0) "DOM_CALLBACK" else "RESULTS"))
                .also { count -> AppLog.i("Diagnostics", "Reserve music probe · stage=complete · rows=$count · callbacks=$callbacks") }
        } catch (timeout: MuzmoDiagnosticTimeout) {
            AppLog.i("Diagnostics", "Reserve music probe · stage=${timeout.stage} · callbacks=$callbacks")
            throw timeout
        } finally {
            pageReady.cancel()
            result.cancel()
            browser.stopLoading()
            browser.destroy()
        }
    }

    companion object {
        private fun parseData(raw: String?): JSONObject? = runCatching {
            val inner = JSONTokener(raw.orEmpty()).nextValue() as? String ?: return@runCatching null
            JSONObject(inner)
        }.getOrNull()

        internal fun parseCount(raw: String?): Int? = parseData(raw)?.optJSONArray("tracks")?.length()
    }
}

internal class MuzmoDiagnosticTimeout(val stage: String) : IOException("Muzmo $stage timeout")
