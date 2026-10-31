package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class VideoProjectStage2Test {
    private fun clip(id: String, total: Long = 10000L) =
        VideoClipDraft(id, "content://video/$id", total)

    @Test fun insertClipsInSequenceAndMapTimelineBoundaries() {
        val first = VideoProjectDraft.single("one", "content://video/one", 10000)
        val next = first.addAfter("one", listOf(clip("two", 7000), clip("three", 3000)))
        assertEquals(listOf("one", "two", "three"), next.clips.map { it.id })
        assertEquals(20000, next.durationMs)
        assertEquals(1, next.locateAt(10000).clipIndex)
        assertEquals(2, next.locateAt(17000).clipIndex)
        assertEquals(17000, next.timelineStart(2))
    }

    @Test fun splitPreservesExactDurationAndSourceContinuity() {
        val start = VideoProjectDraft.single("one", "content://video/one", 16000)
            .trimClip("one", 2000, 12000)
        val split = start.splitAt("one", 6800, "right")
        assertEquals(10000L, split.durationMs)
        assertEquals(listOf(2000L, 6800L), split.clips.map { it.inMs })
        assertEquals(listOf(6800L, 12000L), split.clips.map { it.outMs })
        assertEquals(6800, split.locateAt(4800).absoluteSourceMs)
        assertEquals(10000, start.durationMs)
    }

    @Test fun splittingTooNearEndIsRejected() {
        val p = VideoProjectDraft.single("a", "content://a", 10000)
        assertThrows(IllegalArgumentException::class.java) { p.splitAt("a", 40, "b") }
        assertThrows(IllegalArgumentException::class.java) { p.splitAt("a", 9980, "b") }
    }

    @Test fun duplicateKeepsOriginalAndUsesDifferentIdentity() {
        val p = VideoProjectDraft.single("a", "content://a", 10000)
            .trimClip("a", 1000, 8000)
        val doubled = p.duplicate("a", "b")
        assertEquals(listOf("a", "b"), doubled.clips.map { it.id })
        assertEquals(doubled.clips[0].sourceUri, doubled.clips[1].sourceUri)
        assertEquals(doubled.clips[0].inMs, doubled.clips[1].inMs)
        assertEquals(14000L, doubled.durationMs)
    }

    @Test fun reorderChangesExportOrderButPreservesAllEdits() {
        val p = VideoProjectDraft(listOf(clip("a"), clip("b", 3000), clip("c", 5000)))
        val moved = p.move("c", 0)
        assertEquals(listOf("c", "a", "b"), moved.clips.map { it.id })
        assertEquals(p.durationMs, moved.durationMs)
        assertEquals("c", moved.locateAt(0).clipId)
        assertSame(p, p.move("a", 0))
    }

    @Test fun replaceChangesOnlySelectedClipAndResetsOldTrimming() {
        val p = VideoProjectDraft(listOf(clip("a"), clip("b")))
            .trimClip("b", 2000, 5000)
        val replaced = p.replace("b", "content://new", 7000)
        assertEquals("content://new", replaced.clips[1].sourceUri)
        assertEquals(0L, replaced.clips[1].inMs)
        assertEquals(7000L, replaced.clips[1].outMs)
        assertEquals(p.clips[0], replaced.clips[0])
    }

    @Test fun deletePreservesAtLeastOneClipAndDoesNotMutateDraft() {
        val p = VideoProjectDraft(listOf(clip("a"), clip("b")))
        assertEquals(listOf("b"), p.delete("a").clips.map { it.id })
        assertEquals(2, p.clips.size)
        assertThrows(IllegalArgumentException::class.java) { p.delete("a").delete("b") }
    }

    @Test fun invalidOperationsAreRejectedBeforeChangingSources() {
        val p = VideoProjectDraft.single("a", "content://a", 1000)
        assertThrows(IllegalArgumentException::class.java) { p.duplicate("a", "a") }
        assertThrows(IllegalArgumentException::class.java) { p.move("a", 9) }
        assertThrows(IllegalArgumentException::class.java) { p.addAfter("missing", listOf(clip("b"))) }
        assertThrows(IllegalArgumentException::class.java) { p.replace("missing", "content://b", 5000) }
    }
}
