package com.domenota.medialoader.ui.screens.music
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test
class MusicTrackVisibilityTest {
    private val row = DownloadEntity("a", "device", "a.mp3", MediaType.AUDIO, DownloadState.COMPLETED, "content://media/audio/1", 10, 0)
    @Test fun hideAndRestoreOnlySelectedTrack() {
        val hidden = MusicTrackVisibility.update(emptySet(), row, true)
        assertTrue(MusicTrackVisibility.hidden(row, hidden))
        assertFalse(MusicTrackVisibility.hidden(row.copy(id="b", savedUri="content://media/audio/2"), hidden))
        assertFalse(MusicTrackVisibility.hidden(row, MusicTrackVisibility.update(hidden, row, false)))
    }
    @Test fun rescannedAliasAndRenamedFileRemainHidden() {
        val hidden = MusicTrackVisibility.update(emptySet(), row, true)
        assertTrue(MusicTrackVisibility.hidden(row.copy(id="new-canonical"), hidden))
        assertTrue(MusicTrackVisibility.hidden(row.copy(savedUri="file:///renamed.mp3"), hidden))
    }
    @Test fun restoreRescannedAliasAlsoClearsOriginalIdentity() {
        val hidden = MusicTrackVisibility.update(emptySet(), row, true)
        val restored = MusicTrackVisibility.update(hidden, row.copy(id="new-canonical"), false)
        assertTrue(restored.isEmpty())
        assertFalse(MusicTrackVisibility.hidden(row, restored))
    }
}
