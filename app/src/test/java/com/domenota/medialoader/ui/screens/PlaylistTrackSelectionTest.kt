package com.domenota.medialoader.ui.screens

import com.domenota.medialoader.ui.screens.music.PlaylistTrackSelectionState
import org.junit.Assert.*
import org.junit.Test

class PlaylistTrackSelectionTest {
    @Test fun selectionAcrossSourcesUsesOneIdentityAndPreservesSelectionOrder() {
        val aliases = mapOf("phone:audio:42" to "download")
        val first = PlaylistTrackSelectionState().toggle("phone:audio:42", emptySet(), aliases)
        val second = first.toggle("other", emptySet(), aliases)
        assertEquals(listOf("download", "other"), second.selectedKeys)
        assertEquals(listOf("other"), second.toggle("download", emptySet(), aliases).selectedKeys)
        assertEquals(listOf("download", "other"), second.selectedKeys)
    }
    @Test fun existingMembersCannotBeSelectedAndUnavailableSelectionDoesNotBecomePlayable() {
        val state = PlaylistTrackSelectionState().toggle("existing", setOf("existing"))
            .toggle("good", setOf("existing")).toggle("gone", setOf("existing"))
        val result = state.confirm(setOf("existing", "good"), setOf("existing"))
        assertEquals(listOf("good"), result.addedKeys)
        assertEquals(listOf("gone"), result.unavailableKeys)
        assertEquals(listOf("good", "gone"), state.selectedKeys)
    }
    @Test fun resolvingAnAliasAfterSelectionDoesNotIntroduceDuplicateMembership() {
        val state = PlaylistTrackSelectionState(listOf("phone:audio:42", "download", "other"))
            .canonicalize(mapOf("phone:audio:42" to "download"))
        assertEquals(listOf("download", "other"), state.selectedKeys)
    }
}
