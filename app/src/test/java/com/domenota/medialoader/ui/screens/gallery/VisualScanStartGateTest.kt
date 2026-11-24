package com.domenota.medialoader.ui.screens.gallery

import org.junit.Assert.*
import org.junit.Test

class VisualScanStartGateTest {
    private fun reason(ready:Boolean=true, permission:Boolean=true, loaded:Boolean=true,
        saving:Boolean=false, trash:Boolean=false, hasFiles:Boolean=true, generation:Boolean=true,
        running:Boolean=false) =
        VisualScanStartGate.blocker(ready,permission,loaded,saving,trash,hasFiles,generation,running)

    @Test fun permitsOnlyCompleteCurrentCatalog() {
        assertNull(reason())
        assertEquals(VisualScanStartGate.Blocker.CATALOG,reason(ready=false))
        assertEquals(VisualScanStartGate.Blocker.CATALOG,reason(generation=false))
        assertEquals(VisualScanStartGate.Blocker.EMPTY,reason(hasFiles=false))
    }

    @Test fun blocksConcurrentOperationsAndPermissionLoss() {
        assertEquals(VisualScanStartGate.Blocker.PERMISSION,reason(permission=false,ready=false))
        assertEquals(VisualScanStartGate.Blocker.RUNNING,reason(running=true))
        assertEquals(VisualScanStartGate.Blocker.RESTORE,reason(loaded=false))
        assertEquals(VisualScanStartGate.Blocker.WRITE,reason(saving=true))
        assertEquals(VisualScanStartGate.Blocker.TRASH,reason(trash=true))
    }
}
