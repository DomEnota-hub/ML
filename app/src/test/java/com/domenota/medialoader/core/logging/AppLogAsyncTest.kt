package com.domenota.medialoader.core.logging

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppLogAsyncTest {
    @Test fun exportFlushesOrderedWritesAndStillRedactsExceptionCauses() {
        AppLog.init(RuntimeEnvironment.getApplication())
        AppLog.clear()
        AppLog.i("Perf", "first sessionid=secret")
        AppLog.e("Perf", "second", IllegalStateException("outer",
            IllegalArgumentException("sessionid=private")))
        val report = AppLog.exportMarkdown()
        assertTrue(report.indexOf("first") < report.indexOf("second"))
        assertTrue(report.contains("sessionid=<redacted>"))
        assertFalse(report.contains("sessionid=secret"))
        assertFalse(report.contains("sessionid=private"))
        assertTrue(report.contains("Caused by:"))
        assertTrue(report.contains("[thread="))
        assertTrue(report.contains("flush=true"))
    }
    @Test fun clearIsOrderedWithWritesAndSubsequentEventsRemain() {
        AppLog.init(RuntimeEnvironment.getApplication())
        AppLog.i("Perf", "before-clear")
        AppLog.clear()
        AppLog.i("Perf", "after-clear")
        val report = AppLog.readText()
        assertFalse(report.contains("before-clear"))
        assertTrue(report.contains("after-clear"))
    }
}
