package com.domenota.medialoader.data.catalog.visibility

import android.content.pm.ServiceInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/** Regression for Android 15+ dataSync quota exhaustion. A stale service
 * startId must never prevent the mandatory unconditional stop. The callback
 * does not initialize Room or implicitly resume/delete pending file journals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class FolderVisibilityServiceTimeoutTest {
    @Test fun dataSyncTimeoutStopsUnconditionallyEvenWithStaleStartId() {
        val controller=Robolectric.buildService(FolderVisibilityService::class.java).create()
        val service=controller.get()
        try {
            service.onTimeout(3,ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            val shadow=Shadows.shadowOf(service)
            assertTrue("Android must see an unconditional stopSelf()",shadow.isStoppedBySelf)
            assertNotEquals("stopSelfResult(startId) can fail for a stale id",
                3,shadow.stopSelfResultId)
        } finally {
            controller.destroy()
        }
    }
}
