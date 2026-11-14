package com.domenota.medialoader.core.logging

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Foreground-only diagnostics; never logs media names, paths or typed text. */
object PerformanceLog {
    @Volatile private var screen = "startup"
    @Volatile private var active = false
    @Volatile private var lastAction = "startup"
    private val main = Handler(Looper.getMainLooper())
    private val generation = AtomicLong()
    private val epoch = AtomicLong()
    private val acknowledged = AtomicLong()
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ML-ui-watchdog").apply { isDaemon = true }
    }
    private var task: java.util.concurrent.ScheduledFuture<*>? = null
    fun screen(name: String) { screen = name }
    fun begin(operation: String): Long {
        val started = SystemClock.uptimeMillis()
        lastAction = operation
        AppLog.i("Perf", "BEGIN · action=$operation · screen=$screen")
        return started
    }
    fun end(operation: String, started: Long, details: String = "") {
        AppLog.i("Perf", "END · action=$operation · ms=${SystemClock.uptimeMillis() - started} · screen=$screen · $details · ${memory()}")
    }
    fun slow(operation: String, started: Long, thresholdMs: Long = 100) {
        if (SystemClock.uptimeMillis() - started >= thresholdMs) end(operation, started, "slow=true")
    }
    fun now(): Long = SystemClock.uptimeMillis()
    private fun memory(): String {
        val runtime = Runtime.getRuntime()
        return "heapUsedMiB=${(runtime.totalMemory() - runtime.freeMemory()) / 1048576} · heapMaxMiB=${runtime.maxMemory() / 1048576}"
    }
    fun start() {
        if (active) return
        active = true
        val session = epoch.incrementAndGet()
        acknowledged.set(0L)
        var waiting = false
        var sent = 0L
        var token = 0L
        var reported = false
        val stall = StallWindow()
        task = worker.scheduleWithFixedDelay({
            if (!active || epoch.get() != session) return@scheduleWithFixedDelay
            val now = SystemClock.uptimeMillis()
            if (waiting && acknowledged.get() == token) {
                if (reported) AppLog.i("Perf", "UI_RECOVERED · blockedMs=${now - sent} · screen=$screen")
                waiting = false; reported = false; stall.reset()
            }
            if (!waiting) {
                token = generation.incrementAndGet(); val ping = token
                sent = now; waiting = true
                main.post { if (active && epoch.get() == session) acknowledged.set(ping) }
            } else if (stall.shouldReport(now - sent)) {
                reported = true
                val stack = Looper.getMainLooper().thread.stackTrace.take(36).joinToString("\n") { "    at $it" }
                AppLog.w("Perf", "UI_STALL · blockedMs=${now - sent} · screen=$screen · lastAction=$lastAction · ${memory()}\n$stack")
            }
        }, 0, 250, TimeUnit.MILLISECONDS)
        frameStart = 0L; frameWindow = 0L; slowFrames = 0; worstFrameMs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }
    fun stop() {
        if (active) AppLog.i("Perf", "LIBRARY_WORK · ${LibraryWorkCounters.summary()}")
        active = false; epoch.incrementAndGet(); task?.cancel(false); task = null
        Choreographer.getInstance().removeFrameCallback(frameCallback)
    }
    private var frameStart = 0L
    private var frameWindow = 0L
    private var slowFrames = 0
    private var worstFrameMs = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            val ms = frameTimeNanos / 1_000_000
            if (frameStart > 0) {
                val gap = ms - frameStart
                if (gap >= 50) { slowFrames++; worstFrameMs = maxOf(worstFrameMs, gap) }
            }
            frameStart = ms
            if (frameWindow == 0L) frameWindow = ms
            if (ms - frameWindow >= 10_000) {
                if (slowFrames > 0) AppLog.i("Perf", "FRAME_GAPS · windowMs=${ms - frameWindow} · over50ms=$slowFrames · worstMs=$worstFrameMs · screen=$screen")
                frameWindow = ms; slowFrames = 0; worstFrameMs = 0
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }
}
