package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class UnifiedMediaLibraryTest {
    private fun ref(id: String, uri: String, date: Long = 10, app: Boolean = false,
                    aliases: Set<String> = emptySet()) = LibraryMediaRef(
        key = id, aliases = aliases + "uri:$uri",
        item = DownloadEntity(id, if (app) "instagram" else "device", "same.jpg", MediaType.PHOTO,
            DownloadState.COMPLETED, uri, 120, date), appDownloadId = id.takeIf { app },
    )

    @Test fun samePhysicalFileKeepsDownloadMetadataAndEveryRouteAlias() {
        val download = ref("download", "content://downloads/4", app = true, aliases = setOf("media:external:42"))
            .let { it.copy(item = it.item.copy(recognizedTitle = "Recognized title")) }
        val phone = ref("phone:image:42", "content://media/external/images/media/42", aliases = setOf("media:external:42"))
        val result = UnifiedMediaLibrary.merge(listOf(download), listOf(phone), emptySet()).single()
        assertEquals("download", result.key)
        assertEquals("Recognized title", result.item.recognizedTitle)
        assertTrue(result.aliases.containsAll(listOf("download", "phone:image:42", "uri:content://downloads/4")))
    }

    @Test fun equalNamesAndSizesDoNotMergeDifferentFiles() {
        val result = UnifiedMediaLibrary.merge(listOf(ref("app", "content://downloads/4", app = true)),
            listOf(ref("phone:image:42", "content://media/external/images/media/42")), emptySet())
        assertEquals(listOf("app", "phone:image:42"), result.map { it.key })
    }

    @Test fun privateIdentityBlocksEveryAliasEvenAcrossAnIntermediateReference() {
        val app = ref("app", "content://downloads/4", app = true, aliases = setOf("physical:4"))
        val bridge = ref("bridge", "content://media/42", aliases = setOf("physical:4", "canonical:42"))
        val phone = ref("phone:image:42", "content://documents/42", aliases = setOf("canonical:42"))
        val safe = ref("safe", "content://media/99")
        assertEquals(listOf("safe"), UnifiedMediaLibrary.merge(listOf(app), listOf(bridge, phone, safe),
            setOf("canonical:42")).map { it.key })
        assertTrue(UnifiedMediaLibrary.merge(listOf(app.copy(item = app.item.copy(isPrivate = true))),
            listOf(bridge, phone), emptySet()).isEmpty())
    }

    @Test fun orderingIsChronologicalAndStableAcrossInputOrder() {
        val a = ref("a", "content://a", 10)
        val b = ref("b", "content://b", 20)
        val c = ref("c", "content://c", 20)
        assertEquals(listOf("b", "c", "a"), UnifiedMediaLibrary.merge(emptyList(), listOf(a, c, b), emptySet()).map { it.key })
        assertEquals(listOf("b", "c", "a"), UnifiedMediaLibrary.merge(emptyList(), listOf(b, a, c), emptySet()).map { it.key })
    }

    @Test fun missingPhoneScanDoesNotRemoveValidDownloadAndUnplayableRowsStayOut() {
        val app = ref("app", "content://downloads/4", app = true)
        val pending = ref("pending", "content://pending").let { it.copy(item = it.item.copy(state = DownloadState.QUEUED)) }
        val hidden = ref("hidden", "content://hidden").let { it.copy(item = it.item.copy(hidden = true)) }
        val noUri = ref("none", "content://none").let { it.copy(item = it.item.copy(savedUri = null)) }
        assertEquals(listOf("app"), UnifiedMediaLibrary.merge(listOf(app), listOf(pending, hidden, noUri), emptySet()).map { it.key })
    }
}
