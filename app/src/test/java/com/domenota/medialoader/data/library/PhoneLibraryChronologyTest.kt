package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneLibraryChronologyTest {
    private fun row(id: String, uri: String, date: Long,
                    type: MediaType = MediaType.PHOTO) = DownloadEntity(
        id = id, providerId = "test", originalName = "$id.jpg", mediaType = type,
        state = DownloadState.COMPLETED, savedUri = uri, sizeBytes = 300L,
        createdAtEpochMillis = date)

    @Test fun alreadyMigratedDownloadsRecoverChronologyInsideMixedPhoneGallery() {
        val uriOld = "content://media/external/images/media/1"
        val uriNew = "content://media/external/images/media/2"
        val phone = listOf(
            row("phone:old", uriOld, 900_000L),
            row("phone:other", "content://media/external/images/media/8", 600_000L),
            row("phone:new", uriNew, 900_000L))
        val app = listOf(row("history:old", uriOld, 100_000L),
            row("history:new", uriNew, 700_000L))
        val corrected = PhoneLibraryChronology.reconcile(phone, app)
        assertEquals(listOf("phone:new", "phone:other", "phone:old"),
            corrected.map { it.id })
        assertEquals(listOf(700_000L, 600_000L, 100_000L),
            corrected.map { it.createdAtEpochMillis })
        assertEquals(100_000L, app.first().createdAtEpochMillis)
        assertEquals(900_000L, phone.first().createdAtEpochMillis)
        assertEquals(uriOld, corrected.last().savedUri)
    }

    @Test fun nameAndSizeAloneNeverAuthorizeReconciliation() {
        val phone = row("same-name", "content://media/external/images/media/42", 700L)
        val app = row("same-name", "content://media/external/images/media/43", 100L)
        assertEquals(700L,
            PhoneLibraryChronology.reconcile(listOf(phone), listOf(app))
                .single().createdAtEpochMillis)
    }

    @Test fun ambiguousPrivateHiddenOrTypeMismatchedSourcesAreNeverModified() {
        val uri = "content://media/external/images/media/99"
        val phone = row("phone", uri, 900_000L)
        val private = row("private", uri, 100L).copy(isPrivate = true)
        val hidden = row("hidden", uri, 200L).copy(hidden = true)
        val video = row("video", uri, 300L, MediaType.VIDEO)
        val duplicateA = row("duplicate1", uri, 300L)
        val duplicateB = row("duplicate2", uri, 400L)
        for (rows in listOf(listOf(private), listOf(hidden),
            listOf(video), listOf(duplicateA, duplicateB))) {
            assertEquals(phone, PhoneLibraryChronology.reconcile(listOf(phone), rows).single())
        }
    }

    @Test fun unifiedGalleryUsesRecordedDateAndKeepsOtherPhoneImagesInterleaved() {
        val migrated = row("history:2019", "content://media/external/images/media/19", 100L)
        val fresh = row("history:2026", "content://media/external/images/media/26", 900L)
        val external = row("phone:2023", "content://media/external/images/media/23", 500L)
        val phone = PhoneLibraryChronology.reconcile(listOf(
            migrated.copy(id = "phone:migrated", createdAtEpochMillis = 1000L),
            fresh.copy(id = "phone:fresh", createdAtEpochMillis = 1000L),
            external), listOf(migrated, fresh))
        fun ref(item: DownloadEntity, fromApp: Boolean) =
            LibraryMediaRef(item.id, setOf(item.savedUri!!, item.id), item,
                item.id.takeIf { fromApp })
        val merged = UnifiedMediaLibrary.merge(
            listOf(ref(migrated, true), ref(fresh, true)),
            phone.map { ref(it, false) }, emptySet())
        assertEquals(listOf("history:2026", "phone:2023", "history:2019"),
            merged.map { it.key })
        assertEquals(listOf(900L, 500L, 100L),
            merged.map { it.item.createdAtEpochMillis })
    }
}
