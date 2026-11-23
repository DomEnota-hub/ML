package com.domenota.medialoader.data.library

import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DuplicatePrivacyRetentionTest {
    private val movedId = "private-download-99"
    private val movedAliases = setOf(
        "uri:content://media/external/audio/media/99",
        "media:external:99"
    )

    private fun journal(uri: String, id: String): File {
        val root = File(RuntimeEnvironment.getApplication().noBackupFilesDir, "duplicate-journal-music")
        check(root.mkdirs() || root.isDirectory)
        val target = File(root, "1000.json")
        target.writeText(JSONObject().put("groups",org.json.JSONArray().put(
            JSONObject().put("tracks",org.json.JSONArray().put(
                JSONObject().put("id", id).put("uri", uri)
            ))
        )).toString())
        return target
    }

    @Test fun movingUnrelatedFileKeepsCompletedPublicReportAvailable() {
        val context = RuntimeEnvironment.getApplication()
        val file = journal("content://media/external/audio/media/17", "public-17")
        try {
            assertTrue(DuplicatePrivacyRetention.provenUnrelated(context, movedId, movedAliases))
            val epoch = DuplicatePrivacyEpoch(12)
            epoch.revokeUnrelated(movedId, movedAliases)
            assertEquals(12L, epoch.generation)
            assertTrue(epoch.accepts(12, "public-17", setOf("media:external:17")))
            assertFalse(epoch.accepts(12, "other-alias", setOf("media:external:99")))
        } finally { file.delete() }
    }

    @Test fun relatedUriAliasForcesFullInvalidation() {
        val context = RuntimeEnvironment.getApplication()
        val file = journal("content://media/external/audio/media/99", "other-library-id")
        try {
            assertFalse(DuplicatePrivacyRetention.provenUnrelated(context, movedId, movedAliases))
        } finally { file.delete() }
    }

    @Test fun sameHistoryIdForcesFullInvalidationEvenWithDifferentUri() {
        val context = RuntimeEnvironment.getApplication()
        val file = journal("content://media/external/audio/media/17", movedId)
        try {
            assertFalse(DuplicatePrivacyRetention.provenUnrelated(context, movedId, movedAliases))
        } finally { file.delete() }
    }

    @Test fun unreadableReportContentFailsClosed() {
        val context = RuntimeEnvironment.getApplication()
        val file = journal("content://media/external/audio/media/17", "public-17")
        try {
            file.writeText("not a JSON report")
            assertFalse(DuplicatePrivacyRetention.provenUnrelated(context, movedId, movedAliases))
        } finally { file.delete() }
    }

    @Test fun absentProofOrUnknownAliasesUseOriginalInvalidation() {
        val context = RuntimeEnvironment.getApplication()
        val file = journal("content://media/external/audio/media/17", "public-17")
        try {
            assertFalse(DuplicatePrivacyRetention.provenUnrelated(context, movedId, emptySet()))
            assertFalse(DuplicatePrivacyRetention.unrelatedMember(movedId, movedAliases,
                "member-without-uri", ""))
        } finally { file.delete() }
    }
}
