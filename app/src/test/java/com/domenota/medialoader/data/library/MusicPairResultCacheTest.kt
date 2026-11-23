package com.domenota.medialoader.data.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MusicPairResultCacheTest {
    private val context:Context = ApplicationProvider.getApplicationContext()
    private val first=ComparisonFrameKey("first","content://media/1",1000,101,
        "a".repeat(64),3,256)
    private val second=ComparisonFrameKey("second","content://media/2",1300,103,
        "b".repeat(64),3,256)
    private val firstFrames=listOf(
        ComparisonFrame(FloatArray(64){.1f},.2f),
        ComparisonFrame(FloatArray(64){.2f},.3f))
    private val secondFrames=listOf(
        ComparisonFrame(FloatArray(64){.1f},.2f),
        ComparisonFrame(FloatArray(64){.3f},.4f))
    private val result=AlignedAudioComparison(256,firstFrames,secondFrames,
        listOf(ComparisonColumn(0,0,false),ComparisonColumn(1,1,true)),
        listOf(ComparisonZone(1,2)),12,emptyList(),
        setOf(ComparisonZone(1,2)))

    @Before fun reset() { MusicPairResultCache.clearForPrivacy(context) }

    @Test fun completedComparisonSurvivesNewReadWithoutRecalculation() {
        assertTrue(MusicPairResultCache.valid(result))
        assertNull(MusicPairResultCache.read(context,first,second,firstFrames,secondFrames))
        MusicPairResultCache.write(context,first,second,result)
        val restored=MusicPairResultCache.read(context,first,second,firstFrames,secondFrames)
        assertNotNull(restored)
        assertEquals(result.columns,restored?.columns)
        assertEquals(result.zones,restored?.zones)
        assertEquals(result.confirmedShortZones,restored?.confirmedShortZones)
        assertSame(firstFrames,restored?.first)
    }
    @Test fun changingFileOrEpochOrDirectionProducesCacheMiss() {
        MusicPairResultCache.write(context,first,second,result)
        assertNull(MusicPairResultCache.read(context,first.copy(modified=999),second,firstFrames,secondFrames))
        assertNull(MusicPairResultCache.read(context,first.copy(sha="c".repeat(64)),second,firstFrames,secondFrames))
        assertNull(MusicPairResultCache.read(context,first.copy(epoch=4),second.copy(epoch=4),firstFrames,secondFrames))
        assertNull(MusicPairResultCache.read(context,second,first,secondFrames,firstFrames))
        assertNotNull(MusicPairResultCache.read(context,first,second,firstFrames,secondFrames))
    }
    @Test fun corruptDiskAndWrongFrameShapeFailClosed() {
        MusicPairResultCache.write(context,first,second,result)
        val directory=java.io.File(context.noBackupFilesDir,"duplicate-comparison-pairs-v2")
        val file=directory.listFiles()!!.single()
        val bytes=file.readBytes()
        bytes[bytes.size-1]=(bytes.last().toInt() xor 1).toByte()
        file.writeBytes(bytes)
        assertNull(MusicPairResultCache.read(context,first,second,firstFrames,secondFrames))
        MusicPairResultCache.write(context,first,second,result)
        assertNull(MusicPairResultCache.read(context,first,second,firstFrames.dropLast(1),secondFrames))
    }
    @Test fun revocationRemovesSavedResult() {
        MusicPairResultCache.write(context,first,second,result)
        assertNotNull(MusicPairResultCache.read(context,first,second,firstFrames,secondFrames))
        MusicPairResultCache.clearForPrivacy(context)
        assertNull(MusicPairResultCache.read(context,first,second,firstFrames,secondFrames))
    }
    @Test fun rejectsInvalidColumnsAndNonConfirmedRegions() {
        assertFalse(MusicPairResultCache.valid(result.copy(
            columns=listOf(ComparisonColumn(3,null,true)))))
        assertFalse(MusicPairResultCache.valid(result.copy(
            confirmedShortZones=setOf(ComparisonZone(0,1)))))
        assertFalse(MusicPairResultCache.valid(result.copy(
            zones=listOf(ComparisonZone(2,3)))))
    }
}
