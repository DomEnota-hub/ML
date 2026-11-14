package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class DuplicatePrivacyEpochTest {
    @Test fun freshScanWithStaleCatalogAliasCannotBypassRevokedHistoryId() {
        val state = DuplicatePrivacyEpoch()
        state.revoke("history-id",setOf("media:external:14","file-stat:device:inode"))
        val freshEpoch = state.generation
        assertFalse(state.accepts(freshEpoch,"catalog-id",setOf("uri:alternate","file-stat:device:inode")))
        assertFalse(state.accepts(freshEpoch,"documents-id",setOf("media:external:14")))
        assertTrue(state.accepts(freshEpoch,"unrelated",setOf("media:external:15")))
        state.restored("history-id")
        assertTrue(state.accepts(freshEpoch,"catalog-id",setOf("media:external:14")))
        assertFalse(state.accepts(freshEpoch-1,"catalog-id",setOf("media:external:14")))
    }

    @Test fun staleScanAndQueuedSessionCannotRepublishAfterMoveOrRestore() {
        val state = DuplicatePrivacyEpoch(5)
        val inFlightScan = state.generation
        assertTrue(state.accepts(inFlightScan,"track"))
        state.revoke("track")
        assertFalse(state.accepts(inFlightScan,"track"))
        assertFalse(state.accepts(inFlightScan,"unrelated"))
        // Even a freshly admitted stale UI row cannot enter while the private save is pending.
        assertFalse(state.accepts(state.generation,"track"))
        assertTrue(state.accepts(state.generation,"public"))
        state.restored("track")
        assertTrue(state.accepts(state.generation,"track"))
        assertFalse(state.accepts(inFlightScan,"track"))
    }
    @Test fun persistedEpochAlsoRejectsOldSnapshotsAfterRestart() {
        val before = DuplicatePrivacyEpoch()
        before.revoke("track")
        val restarted = DuplicatePrivacyEpoch(before.generation)
        assertFalse(restarted.accepts(0,"track"))
        assertFalse(restarted.accepts(0,"other"))
    }
}
