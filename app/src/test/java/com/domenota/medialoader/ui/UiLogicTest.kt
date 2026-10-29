package com.domenota.medialoader.ui

import com.domenota.medialoader.core.database.DatabaseConverters
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.ui.model.defaultSelectedIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiLogicTest {
    private fun item(id: String, type: MediaType) = MediaItem(
        id = id, providerId = "instagram", type = type, originalName = id, downloadUrl = "https://scontent.fbcdn.net/$id",
    )

    @Test fun audioIsNeverSelectedByDefault() {
        val items = listOf(
            item("a", MediaType.PHOTO),
            item("b", MediaType.VIDEO),
            item("b_audio", MediaType.AUDIO),
        )
        assertEquals(setOf("a", "b"), items.defaultSelectedIds())
    }

    @Test fun extractsFirstLinkFromSharedText() {
        assertEquals(
            "https://www.instagram.com/reel/Ab_12-z/?igsh=x",
            extractFirstUrl("Смотри https://www.instagram.com/reel/Ab_12-z/?igsh=x."),
        )
        assertNull(extractFirstUrl("просто текст без ссылки"))
    }

    @Test fun enumConvertersRoundTripAndSurviveUnknownValues() {
        val converters = DatabaseConverters()
        DownloadState.values().forEach {
            assertEquals(it, converters.stringToState(converters.stateToString(it)))
        }
        MediaType.values().forEach {
            assertEquals(it, converters.stringToType(converters.typeToString(it)))
        }
        assertEquals(DownloadState.FAILED, converters.stringToState("SOMETHING_NEW"))
    }
}
