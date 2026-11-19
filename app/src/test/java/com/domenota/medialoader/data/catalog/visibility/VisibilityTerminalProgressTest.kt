package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityTerminalProgressTest {
    @Test fun finalCursorRemainsVisibleAfterDurableJournalRemoval() {
        for (count in listOf(1,8,46)) {
            val pending=FolderVisibilityProgress("folder",VisibilityDirection.HIDE,
                count,count,true)
            val complete=visibilityTerminalProgress(pending,null)
            assertFalse(complete.pending)
            assertEquals(count,complete.total)
            assertEquals(count,complete.completed)
            assertNull(complete.folderId)
            assertTrue(complete.members.isEmpty())
            assertEquals(complete,visibilityTerminalProgress(complete,null))
        }
    }
    @Test fun crashedOrIncompleteBatchCannotFabricate100Percent() {
        val before=FolderVisibilityProgress("folder",VisibilityDirection.SHOW,
            7,8,true)
        val missing=visibilityTerminalProgress(before,null)
        assertEquals(0,missing.completed)
        assertEquals(0,missing.total)
        assertFalse(missing.pending)
        val failed=VisibilityFailureInfo(VisibilityOperationStage.PLAN,"FV_PLAN_STATE","plan")
        val clear=visibilityTerminalProgress(FolderVisibilityProgress("folder",VisibilityDirection.HIDE,
            8,8,true),failed)
        assertEquals(0,clear.completed)
        assertEquals(failed,clear.failure)
    }
}
