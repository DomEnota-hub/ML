package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MusicFolderRulesTest {
    @Test fun deviceFolderExclusionDoesNotUseAlbumTagsOrRemoveTheTrack() {
        val row = DownloadEntity("id", "youtube", "song", MediaType.AUDIO, DownloadState.COMPLETED,
            "content://media/audio/1", 100L, 0L, groupId = "unrelated-album")
        val folders = mapOf(row.savedUri!! to "external:Music/Hidden/")
        assertFalse(MusicFolderRules.visible(row, setOf("external:Music/Hidden/"), folders))
        assertTrue(MusicFolderRules.visible(row, emptySet(), folders))
        assertNull(MusicFolderRules.key(row, emptyMap()))
        assertEquals("id", row.id)
    }
}
