package com.domenota.medialoader.data.download

import android.content.Context
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.model.DownloadUiState
import com.domenota.medialoader.ui.model.toUi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class DownloadNetworkPolicyTest {
    @Test fun threeModesAndBoundaryTreatUnknownAsLarge() {
        assertNull(DownloadNetworkPolicy().waiting(null,true,false,false))
        assertEquals(DownloadNetworkWait.WIFI,DownloadNetworkPolicy(DownloadNetworkMode.WIFI).waiting(1,true,false,false))
        for (threshold in DownloadNetworkPolicy.thresholds) {
            val policy=DownloadNetworkPolicy(DownloadNetworkMode.LARGE_WIFI,threshold)
            val boundary=threshold*1024L*1024L
            assertNull(policy.waiting(boundary-1,true,false,false))
            assertEquals(DownloadNetworkWait.WIFI,policy.waiting(boundary,true,false,false))
            assertEquals(DownloadNetworkWait.WIFI,policy.waiting(null,true,false,false))
            assertEquals(DownloadNetworkWait.WIFI,policy.waiting(0,true,false,false))
            assertEquals(DownloadNetworkWait.WIFI,policy.waiting(-1,true,false,false))
            assertNull(policy.waiting(null,true,true,false))
        }
    }
    @Test fun manualOverrideCannotInventInternet() {
        val policy=DownloadNetworkPolicy(DownloadNetworkMode.WIFI)
        assertNull(policy.waiting(null,true,false,true))
        assertEquals(DownloadNetworkWait.INTERNET,policy.waiting(null,false,false,true))
        assertEquals(DownloadNetworkWait.INTERNET,policy.waiting(null,false,true,false))
    }
    @Test fun markerOnlyMeansWaitForQueuedRows() {
        val entity=DownloadEntity("id","instagram","photo.jpg",MediaType.PHOTO,DownloadState.QUEUED,null,null,1,
            errorMessage=DownloadNetworkWait.WIFI.marker)
        val ui=entity.toUi()
        assertEquals(DownloadUiState.QUEUED,ui.state)
        assertEquals("Ожидание Wi-Fi",ui.networkWaitLabel)
        assertTrue(ui.canDownloadNow)
        assertNull(ui.progress)
        val offline=entity.copy(errorMessage=DownloadNetworkWait.INTERNET.marker).toUi()
        assertEquals("Ожидание сети",offline.networkWaitLabel)
        assertFalse(offline.canDownloadNow)
        assertNull(entity.copy(state=DownloadState.FAILED).networkWait)
        assertFalse(entity.copy(state=DownloadState.FAILED).toUi().canDownloadNow)
    }
    @Test fun preferencesRoundTripWithoutChangingDefaults() {
        val context: Context=RuntimeEnvironment.getApplication()
        val prefs=context.getSharedPreferences("download_network",0)
        prefs.edit().clear().commit()
        assertEquals(DownloadNetworkPolicy(),DownloadNetworkPreferences(context).read())
        val expected=DownloadNetworkPolicy(DownloadNetworkMode.LARGE_WIFI,250)
        DownloadNetworkPreferences(context).save(expected)
        assertEquals(expected,DownloadNetworkPreferences(context).read())
        prefs.edit().putString("mode","bad").putInt("threshold_mb",12).commit()
        assertEquals(DownloadNetworkPolicy(),DownloadNetworkPreferences(context).read())
        prefs.edit().clear().commit()
    }
}
