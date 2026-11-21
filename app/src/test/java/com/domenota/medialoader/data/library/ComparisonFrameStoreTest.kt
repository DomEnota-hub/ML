package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ComparisonFrameStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = ComparisonFrameKey("id", "content://media/1", 100L, 200L, "a".repeat(64), 7L, 256L)
    private val frames = listOf(ComparisonFrame(FloatArray(64) { if (it == 2) 1f else 0f }, .42f),
        ComparisonFrame(FloatArray(0), 0f))
    private fun assertFrames(value: List<ComparisonFrame>?) {
        assertNotNull(value)
        assertEquals(frames.size, value!!.size)
        frames.zip(value).forEach { (expected, actual) ->
            assertEquals(expected.rms, actual.rms, 0f)
            assertArrayEquals(expected.spectrum, actual.spectrum, 0f)
        }
    }
    @Test fun independentTrackSurvivesNewPairAndProcessRestart() {
        val dir = temporary.newFolder()
        val store = ComparisonFrameStore(dir)
        store.write(key, frames)
        store.write(key.copy(id = "other", uri = "content://media/2"), frames)
        assertFrames(store.read(key))
        assertFrames(ComparisonFrameStore(dir).read(key))
    }
    @Test fun identityIncludesSourceModificationHashEpochAndResolution() {
        val store = ComparisonFrameStore(temporary.newFolder())
        store.write(key, frames)
        listOf(key.copy(id="other"), key.copy(uri="content://media/2"), key.copy(bytes=101),
            key.copy(modified=201), key.copy(sha="b".repeat(64)), key.copy(epoch=8), key.copy(stepMs=512))
            .forEach { assertNull(store.read(it)) }
        assertFrames(store.read(key))
    }
    @Test fun corruptOrTruncatedPayloadCannotBeUsed() {
        val dir = temporary.newFolder()
        val store = ComparisonFrameStore(dir)
        store.write(key, frames)
        val path = dir.listFiles()!!.single()
        val bytes = path.readBytes()
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        path.writeBytes(bytes)
        assertNull(ComparisonFrameStore(dir).read(key))
        store.write(key, frames)
        dir.listFiles()!!.single().writeBytes(byteArrayOf(1, 2, 3))
        assertNull(ComparisonFrameStore(dir).read(key))
    }
    @Test fun unknownOrStaleStampCannotProducePersistentKey() {
        val track = DuplicateTrack(key.id, "title", null, "file.mp3", key.uri, key.bytes, key.modified,
            key.sha, null, 1000L, null, null, privacyEpoch=key.epoch)
        assertEquals(key, ComparisonFrameKey.verified(track, 100L to 200L, 256L))
        assertNull(ComparisonFrameKey.verified(track.copy(modified=null), 100L to 200L, 256L))
        assertNull(ComparisonFrameKey.verified(track, 100L to null, 256L))
        assertNull(ComparisonFrameKey.verified(track, 101L to 200L, 256L))
        assertNull(ComparisonFrameKey.verified(track, 100L to 201L, 256L))
        assertNull(ComparisonFrameKey.verified(track.copy(sha=""), 100L to 200L, 256L))
    }
    @Test fun privacyClearRemovesMemoryAndDiskAndDiskBudgetIsBounded() {
        val dir = temporary.newFolder()
        val store = ComparisonFrameStore(dir, diskLimitBytes=700L)
        repeat(8) { store.write(key.copy(id="track-$it"), frames) }
        assertTrue(dir.listFiles()!!.sumOf { it.length() } <= 700L)
        assertFrames(store.read(key.copy(id="track-7")))
        assertTrue(store.clear())
        assertNull(store.read(key.copy(id="track-7")))
        assertNull(ComparisonFrameStore(dir).read(key.copy(id="track-7")))
    }
}
