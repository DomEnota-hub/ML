package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityPlanSizePolicyTest {
    @Test fun threeLengthsMustMatchExactly() {
        assertNull(classifyVisibilityPlanSize(42L,42L,42L))
        assertEquals(VisibilityPlanSizeMismatch.CATALOG_ONLY, classifyVisibilityPlanSize(0L,42L,42L))
        assertEquals(VisibilityPlanSizeMismatch.MEDIASTORE_ONLY, classifyVisibilityPlanSize(42L,0L,42L))
        assertEquals(VisibilityPlanSizeMismatch.STREAM_ONLY, classifyVisibilityPlanSize(42L,42L,40L))
        assertEquals(VisibilityPlanSizeMismatch.ALL_DIFFERENT, classifyVisibilityPlanSize(1L,2L,3L))
        assertEquals(VisibilityPlanSizeMismatch.CATALOG_ONLY, classifyVisibilityPlanSize(-1L,42L,42L))
        assertEquals(VisibilityPlanSizeMismatch.CATALOG_ONLY, classifyVisibilityPlanSize(null,42L,42L))
    }
    @Test fun failedPlanUsesSafeTypedErrorCode() {
        for (kind in VisibilityPlanSizeMismatch.entries) {
            val error = VisibilityOperationFailure.wrap(VisibilityOperationStage.PLAN,
                VisibilityPlanSizeFailure(kind))
            assertEquals("FV_PLAN_SIZE_${kind.name}", error.info.code)
            assertFalse(error.info.message.contains("file://"))
            assertFalse(error.info.code.contains("42"))
        }
    }
    @Test fun noMutationRefreshRequiresTwoEmptyJournalsAndPlanStage() {
        assertTrue(canSkipVisibilityPlanFailureRefresh(VisibilityOperationStage.PLAN,false,true))
        assertFalse(canSkipVisibilityPlanFailureRefresh(VisibilityOperationStage.PLAN,false,false))
        assertFalse(canSkipVisibilityPlanFailureRefresh(VisibilityOperationStage.PLAN,true,true))
        assertFalse(canSkipVisibilityPlanFailureRefresh(VisibilityOperationStage.COPY,false,true))
        assertFalse(canSkipVisibilityPlanFailureRefresh(VisibilityOperationStage.REBIND,false,true))
    }
}
