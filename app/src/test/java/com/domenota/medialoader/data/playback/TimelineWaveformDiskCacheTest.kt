package com.domenota.medialoader.data.playback
import com.domenota.medialoader.data.tasks.TaskFailure
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
class TimelineWaveformDiskCacheTest {
    @get:Rule val temp=TemporaryFolder()
    @Test fun confirmedCacheReceiptDetectsDeletionAndChangedCache(){
        val file=File(temp.newFolder(),"track.wave")
        TimelineWaveformDiskCache.prepare(file,2){listOf(.2f,.8f)}
        val receipt=setOf(requireNotNull(WaveformCacheReceipt.stamp(file)))
        assertTrue(WaveformCacheReceipt.current(file,receipt))
        file.appendText(",bad")
        assertFalse(WaveformCacheReceipt.current(file,receipt))
        file.delete()
        assertFalse(WaveformCacheReceipt.current(file,receipt))
    }
    @Test fun realTimelineCacheIsWrittenAndReusedWithoutDecode(){
        val file=File(temp.newFolder(),"track.wave")
        val peaks=listOf(.2f,.8f)
        val first=TimelineWaveformDiskCache.prepare(file,2){peaks}
        assertEquals(WaveformOutcome.GENERATED,first.outcome)
        assertEquals(peaks,TimelineWaveformDiskCache.read(file,2))
        assertEquals(WaveformOutcome.CACHED,TimelineWaveformDiskCache.prepare(file,2){error("cached")}.outcome)
    }
    @Test fun failedWriteAndInvalidDecodeHaveDifferentReasons(){
        val impossible=File(temp.newFile(),"track.wave")
        assertEquals(TaskFailure.CACHE_WRITE,TimelineWaveformDiskCache.prepare(impossible,2){listOf(.2f,.8f)}.failure)
        assertEquals(TaskFailure.DECODE,TimelineWaveformDiskCache.prepare(File(temp.newFolder(),"bad.wave"),2){listOf(Float.NaN,.2f)}.failure)
    }
    @Test fun changedContentNeverPublishesObsoleteCache(){
        val file=File(temp.newFolder(),"track.wave")
        assertEquals(TaskFailure.IO,TimelineWaveformDiskCache.prepare(file,2,sameVersion={false}){listOf(.2f,.8f)}.failure)
        assertFalse(file.exists())
    }
    @Test fun cachedWaveformIsNotReturnedAfterSourceVersionChanges(){
        val file=File(temp.newFolder(),"track.wave")
        TimelineWaveformDiskCache.prepare(file,2){listOf(.2f,.8f)}
        val result=TimelineWaveformDiskCache.prepare(file,2,sameVersion={false}){error("no decode")}
        assertEquals(TaskFailure.IO,result.failure)
        assertTrue(result.peaks.isEmpty())
    }
    @Test fun malformedExtraColumnsAreNotAValidCacheAndSilenceIsValid(){
        val file=File(temp.newFolder(),"track.wave")
        file.writeText("0.1,garbage,0.2")
        assertNull(TimelineWaveformDiskCache.read(file,2))
        assertEquals(WaveformOutcome.GENERATED,TimelineWaveformDiskCache.prepare(file,2){listOf(0f,0f)}.outcome)
        assertEquals(listOf(0f,0f),TimelineWaveformDiskCache.read(file,2))
    }
}
