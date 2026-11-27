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
    @Test fun typedHistoryRoundTripsWithoutExecutableOrPrivateMetadata(){
        val details=TaskDetails(TaskCorrelation.create(),100,TaskTrigger.BACKGROUND,TaskFailure.CACHE_WRITE,TaskOutcomes(2,1,0,1),true)
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
