package com.domenota.medialoader.data.catalog.visibility

import org.junit.Assert.*
import org.junit.Test

class VisibilityPortTelemetryTest {
    @Test fun stageMetricContainsNoPrivateLocatorOrFilename() {
        val secret="secret-very-private-photo-name.jpg"
        val uri="content://user-storage/secret-personal-portrait"
        val item=VisibilityMoveRecord("op0007","folder","item",VisibilityDirection.HIDE,
            uri,secret,1000L,VisibilityFingerprint(42L,"a".repeat(64)),"tok")
        val metric=visibilityPortMetric(VisibilityOperationStage.CLEANUP,item,123L,true)
        assertTrue(metric.contains("stage=CLEANUP"))
        assertTrue(metric.contains("elapsedMs=123"))
        assertTrue(metric.contains("success=true"))
        assertFalse(metric.contains(secret))
        assertFalse(metric.contains(uri))
        assertFalse(metric.contains(item.fingerprint.sha256))
        assertFalse(metric.contains(item.destinationToken))
    }
}
