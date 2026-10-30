package com.domenota.medialoader.ui.components

import org.junit.Assert.*
import org.junit.Test

class MediaDeletionPolicyTest {
    private fun phone(id: String) = MediaDeletionTarget("phone:image:$id", "content://media/external/images/media/$id")
    @Test fun phoneSelectionUsesOneSystemConsentOrOneLocalConfirmationWithSpecialAccess() {
        val rows = listOf(phone("1"), phone("2"))
        assertFalse(MediaDeletionPolicy.needsAppConfirmation(36, false, rows))
        assertTrue(MediaDeletionPolicy.needsAppConfirmation(36, true, rows))
        assertTrue(MediaDeletionPolicy.needsAppConfirmation(29, false, rows))
        assertEquals(listOf(rows.map { it.uri }), MediaDeletionPolicy.phoneBatches(rows))
    }
    @Test fun mixedAndAppSelectionsRequireConsentBeforeChangingAppTrash() {
        val app = MediaDeletionTarget("download", "file:///app.jpg")
        assertTrue(MediaDeletionPolicy.needsAppConfirmation(36, false, listOf(app)))
        assertTrue(MediaDeletionPolicy.needsAppConfirmation(36, false, listOf(app, phone("1"))))
        assertEquals(listOf(listOf(phone("1").uri)), MediaDeletionPolicy.phoneBatches(listOf(app, phone("1"), phone("1"))))
    }
    @Test fun largeSelectionSavedStateFitsBinderAndRestoresExactTargets() {
        val entries = (1..11000).flatMap { listOf("phone:image:$it", "content://media/external/images/media/$it") }
        val encoded = MediaSelectionState.encode(entries)
        assertTrue(encoded.size < 128 * 1024)
        assertEquals(entries, MediaSelectionState.decode(encoded))
    }
    @Test fun largeSelectionsRespectPlatformUriLimitWithoutIncludingAppFiles() {
        val batches = MediaDeletionPolicy.phoneBatches((1..2001).map { phone(it.toString()) })
        assertEquals(listOf(2000, 1), batches.map { it.size })
        assertEquals(2001, batches.flatten().distinct().size)
    }
}
