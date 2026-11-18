package com.domenota.medialoader.data.catalog.visibility

import android.content.Intent
import android.app.Service
import android.content.pm.ServiceInfo
import com.domenota.medialoader.data.MediaRepository
import kotlinx.coroutines.Job
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
    @Test fun duplicateServiceStartIsRejectedBeforeClaimingASecondReservedMove() {
        val controller=Robolectric.buildService(FolderVisibilityService::class.java).create()
        val service=controller.get()
        val coordinator=MediaRepository.get(service.applicationContext).folderVisibilityWork
        val slotField=FolderVisibilityWorkCoordinator::class.java.getDeclaredField("commands")
        slotField.isAccessible=true
        val slot=slotField.get(coordinator) as VisibilityWorkCommandSlot
        val reservation=slot.offer(VisibilityWorkCommand.ExplicitResume)
        val oldJob=Job()
        val jobField=FolderVisibilityService::class.java.getDeclaredField("operation")
        jobField.isAccessible=true
        jobField.set(service,oldJob)
        try {
            val intent=Intent(service,FolderVisibilityService::class.java).apply {
                action=FolderVisibilityService.ACTION_RUN_REQUEST
                putExtra(FolderVisibilityService.EXTRA_REQUEST_ID,reservation.id)
            }
            assertEquals(Service.START_NOT_STICKY,service.onStartCommand(intent,0,8))
            assertFalse("A rejected new request must not hold the slot",slot.isBusy())
            assertNull(slot.claim(reservation.id))
        } finally {
            oldJob.cancel()
            controller.destroy()
        }
    }

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
