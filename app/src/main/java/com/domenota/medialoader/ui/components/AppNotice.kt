package com.domenota.medialoader.ui.components

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import com.domenota.medialoader.core.model.RussianCounts
import com.domenota.medialoader.data.catalog.FileBatchResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground-only, no pending FIFO and no retained Activity/Context. */
internal object AppNotice {
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val mutableState = MutableStateFlow(NoticeState())
    val state = mutableState.asStateFlow()
    private val foreground = mutableSetOf<Any>()
    private var sequence = 0L
    private var expire: Runnable? = null
    private var retire: Runnable? = null

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else handler.post { action() }
    }

    fun setForeground(owner: Any, started: Boolean) = onMain {
        if (started) foreground.add(owner) else foreground.remove(owner)
        if (foreground.isEmpty()) clear()
    }

    private fun clearTimers() {
        expire?.let { handler.removeCallbacks(it) }
        retire?.let { handler.removeCallbacks(it) }
        expire = null
        retire = null
    }

    private fun clear() {
        clearTimers()
        mutableState.value = NoticeState()
    }

    fun post(context: Context, message: CharSequence?) {
        val text = message?.toString()?.trim().orEmpty().ifBlank { "Не удалось выполнить действие" }
        val safe = text.replace(Regex("(?:https?://|content://|file://)\\S+", RegexOption.IGNORE_CASE), "ссылка скрыта")
        post(context, NoticeMessage(safe, tone = NoticePolicy.classify(safe)))
    }

    fun postBatch(context: Context, title: String, result: FileBatchResult, verb: String) {
        val text = "$verb: ${RussianCounts.files(result.succeeded)}" +
            if (result.failed > 0) " · Ошибок: ${result.failed}" else ""
        val tone = when {
            result.failed == 0 && result.succeeded > 0 -> NoticeTone.SUCCESS
            result.succeeded == 0 && result.failed > 0 -> NoticeTone.ERROR
            result.failed > 0 -> NoticeTone.WARNING
            else -> NoticeTone.INFO
        }
        post(context, NoticeMessage(title, text, tone))
    }

    private fun post(context: Context, message: NoticeMessage) {
        val app = context.applicationContext
        onMain {
            if (foreground.isEmpty()) return@onMain
            val manager = app.getSystemService(AccessibilityManager::class.java)
            val hold = if (Build.VERSION.SDK_INT >= 29 && manager != null)
                manager.getRecommendedTimeoutMillis(NoticePolicy.HOLD_MS.toInt(),
                    AccessibilityManager.FLAG_CONTENT_TEXT or AccessibilityManager.FLAG_CONTENT_ICONS or
                        AccessibilityManager.FLAG_CONTENT_CONTROLS).toLong().coerceAtLeast(NoticePolicy.HOLD_MS)
            else NoticePolicy.HOLD_MS
            val now = SystemClock.uptimeMillis()
            val next = NoticeEntry(++sequence, message, now, hold)
            clearTimers()
            mutableState.value = NoticePolicy.publish(mutableState.value, next)
            expire = Runnable { mutableState.value = NoticePolicy.dismiss(mutableState.value, next.id) }
                .also { handler.postAtTime(it, next.expiresAt) }
            mutableState.value.departing?.entry?.id?.let { id ->
                retire = Runnable { mutableState.value = NoticePolicy.removeDeparting(mutableState.value, id) }
                    .also { handler.postAtTime(it, now + NoticePolicy.TRANSITION_MS) }
            }
        }
    }

    fun dismiss(id: Long) = onMain {
        if (mutableState.value.active?.id == id) clear()
    }
}
