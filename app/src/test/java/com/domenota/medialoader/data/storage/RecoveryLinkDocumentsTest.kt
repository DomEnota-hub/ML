package com.domenota.medialoader.data.storage

import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class RecoveryLinkDocumentsTest {
    private val catalog = """{"version":1,"entries":[{"key":"stable","uri":"old","aliases":["stable","old"],"favorite":true,"folder":"album","extra":"preserved"}]}"""
    private val playlists = """[{"id":"playlist","name":"name","trackIds":["stable"],"trackReferences":{"stable":["stable","old"]}}]"""
    @Test fun catalogRoundTripAndReplayKeepLogicalIdentityAndUnknownFields() {
        val next = RecoveryLinkDocuments.catalog(catalog, "stable", "old", "new")
        assertEquals(next, RecoveryLinkDocuments.catalog(next, "stable", "old", "new"))
        val row = JSONObject(next).getJSONArray("entries").getJSONObject(0)
        assertEquals("new", row.getString("uri")); assertEquals("stable", row.getString("key"))
        assertTrue(row.getBoolean("favorite")); assertEquals("album", row.getString("folder")); assertEquals("preserved", row.getString("extra"))
        assertEquals(JSONObject(catalog).toString(), RecoveryLinkDocuments.catalog(next, "stable", "new", "old"))
    }
    @Test fun playlistsRoundTripDoesNotReplaceTrackOrPlaylistIds() {
        val next = RecoveryLinkDocuments.playlists(playlists, "stable", "old", "new")
        assertEquals(next, RecoveryLinkDocuments.playlists(next, "stable", "old", "new"))
        val row = JSONArray(next).getJSONObject(0)
        assertEquals("playlist", row.getString("id")); assertEquals("stable", row.getJSONArray("trackIds").getString(0))
        assertEquals(JSONArray(playlists).toString(), RecoveryLinkDocuments.playlists(next, "stable", "new", "old"))
    }
    @Test fun unknownVersionForeignUriAndDuplicateIdentityStopWithoutMutation() {
        for (raw in listOf(catalog.replace("\"version\":1", "\"version\":2"), catalog.replace("\"uri\":\"old\"", "\"uri\":\"foreign\""))) {
            try { RecoveryLinkDocuments.catalog(raw, "stable", "old", "new"); fail() } catch (_: IllegalStateException) { }
        }
        val duplicate = JSONObject(catalog).apply { getJSONArray("entries").put(getJSONArray("entries").getJSONObject(0)) }
        try { RecoveryLinkDocuments.catalog(duplicate.toString(), "stable", "old", "new"); fail() } catch (_: IllegalStateException) { }
    }
}
