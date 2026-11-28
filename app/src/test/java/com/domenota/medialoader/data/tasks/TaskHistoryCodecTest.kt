package com.domenota.medialoader.data.tasks

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class TaskHistoryCodecTest {
    @Test fun alignmentOperationSurvivesHistoryAndHasSpecificTitle() {
        val details=TaskDetails(operation=TaskOperation.LYRICS_ALIGNMENT,outcomes=TaskOutcomes(skipped=1))
        val row=TaskSnapshot(taskId(TaskOwner.LYRICS,"fixture"),TaskKind.LYRICS,TaskStatus.PARTIAL,updatedAt=200,details=details)
        val restored=TaskHistoryStore.decode(TaskHistoryStore.encode(TaskHistoryData(rows=listOf(row)))).rows.single()
        assertEquals(details,restored.details)
        assertEquals("Синхронизация текста песни",TaskCenterPresentation.title(restored))
        assertEquals("Поиск текстов песен",TaskCenterPresentation.title(row.copy(details=null)))
    }
    @Test fun indexingWaitDistinguishesLocalGlobalAndEnergyPolicy() {
        val defaults=TaskSettings(charging=false,batterySaver=true)
        assertEquals(TaskWait.CHARGING,TaskHistoryStore.indexingWait(defaults,true,false,true))
        assertEquals(TaskWait.ENERGY_SAVER,TaskHistoryStore.indexingWait(defaults,true,true,true))
        assertEquals(TaskWait.CHARGING,TaskHistoryStore.indexingWait(defaults.copy(charging=true),false,false,false))
        assertNull(TaskHistoryStore.indexingWait(defaults,false,false,false))
        assertNull(TaskHistoryStore.indexingWait(defaults.copy(batterySaver=false),false,false,true))
    }
    @Test fun typedHistoryRoundTripsWithoutExecutableOrPrivateMetadata(){
        val details=TaskDetails(TaskCorrelation.create(),100,TaskTrigger.BACKGROUND,TaskFailure.CACHE_WRITE,TaskOutcomes(2,1,0,1),true,
            TaskCheckpointSummary().append(TaskOutcomes(2,1,0,1),180))
        val row=TaskSnapshot(taskId(TaskOwner.WAVEFORM,"private/raw"),TaskKind.WAVEFORM,TaskStatus.PARTIAL,errors=1,updatedAt=200,
            details=details,commands=setOf(TaskCommand.RETRY),source=TaskSource.YOUTUBE,media=TaskMedia.AUDIO)
        val json=TaskHistoryStore.encode(TaskHistoryData(rows=listOf(row)))
        val decoded=TaskHistoryStore.decode(json).rows.single()
        assertEquals(details,decoded.details)
        assertTrue(decoded.commands.isEmpty())
        assertNull(decoded.source);assertNull(decoded.media)
        assertFalse(json.toString().contains("private/raw"))
    }
    @Test fun legacyHistoryDoesNotInventResults(){
        val row=TaskSnapshot(taskId(TaskOwner.DOWNLOADS,"old"),TaskKind.DOWNLOAD,TaskStatus.FAILED,errors=1,updatedAt=200)
        val json=TaskHistoryStore.encode(TaskHistoryData(rows=listOf(row)))
        json.getJSONArray("rows").getJSONObject(0).remove("details")
        assertNull(TaskHistoryStore.decode(JSONObject(json.toString())).rows.single().details)
    }
}
