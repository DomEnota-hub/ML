package com.domenota.medialoader.core.logging

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build

/** Android process exit history survives a native crash that kills the VM before AppLog can flush. */
object ProcessExitDiagnostics {
    fun recordPreviousExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("process_exit_diagnostics", Context.MODE_PRIVATE)
            val previous = prefs.getLong("latest_reported_exit", 0L)
            val manager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val exits = manager.getHistoricalProcessExitReasons(app.packageName, 0, 12)
                .filter { it.timestamp > previous }
                .sortedBy { it.timestamp }
            exits.takeLast(6).forEach { info ->
                val kind = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH -> "JAVA_CRASH"
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
                    ApplicationExitInfo.REASON_ANR -> "ANR"
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
                    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
                    else -> "reason_${info.reason}"
                }
                AppLog.w("ProcessExit",
                    "Previous process exit · type=$kind · at=${info.timestamp} · status=${info.status} · pssKiB=${info.pss}")
            }
            exits.lastOrNull()?.let { prefs.edit().putLong("latest_reported_exit", it.timestamp).apply() }
        }.onFailure { AppLog.w("ProcessExit", "Exit history read unavailable: ${it.javaClass.simpleName}") }
    }
}
