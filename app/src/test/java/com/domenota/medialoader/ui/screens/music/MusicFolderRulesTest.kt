package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import org.junit.Assert.*
import org.junit.Test

class MusicFolderRulesTest {
    @Test fun sortUsesDisplayedNameAndCountWithDeterministicTies() {
        val alpha = "external:Music/Z/Alpha/"
        val beta = "external:Music/A/Beta/"
        val gamma = "external:Music/B/Gamma/"
        val counts = mapOf(gamma to 2, beta to 2, alpha to 12)
        val keys = counts.keys
        assertEquals(listOf(alpha, beta, gamma), MusicFolderRules.sortedKeys(keys, counts, MusicFolderSort.NAME_ASC))
        assertEquals(listOf(gamma, beta, alpha), MusicFolderRules.sortedKeys(keys, counts, MusicFolderSort.NAME_DESC))
        assertEquals(listOf(beta, gamma, alpha), MusicFolderRules.sortedKeys(keys, counts, MusicFolderSort.COUNT_ASC))
        assertEquals(listOf(alpha, beta, gamma), MusicFolderRules.sortedKeys(keys, counts, MusicFolderSort.COUNT_DESC))
        assertEquals(listOf(beta, gamma), MusicFolderRules.sortedKeys(keys.filterNot { it == alpha }, counts, MusicFolderSort.COUNT_DESC))
        val anotherAlpha = "external:Music/A/Alpha/"
        assertEquals(listOf(anotherAlpha, alpha), MusicFolderRules.sortedKeys(listOf(alpha, anotherAlpha),
            mapOf(alpha to 1, anotherAlpha to 1), MusicFolderSort.COUNT_DESC))
    }

    @Test fun persistedFolderSortDefaultsAndRoundTrips() {
        assertEquals(MusicFolderSort.NAME_ASC, MusicFolderSort.fromPreference(null))
        assertEquals(MusicFolderSort.NAME_ASC, MusicFolderSort.fromPreference("obsolete"))
        MusicFolderSort.entries.forEach { assertEquals(it, MusicFolderSort.fromPreference(it.name)) }
        assertTrue(MusicFolderRules.sortedKeys(emptyList(), emptyMap(), MusicFolderSort.COUNT_ASC).isEmpty())
    }

    @Test fun shuffleOrderAlwaysChangesAndPreservesMembership() {
        val original=listOf("a","b","c","d")
        var current=original
        repeat(40){
            val next=MusicFolderRules.shuffledOrder(current,kotlin.random.Random(it))
            assertNotEquals(current,next)
            assertEquals(original.toSet(),next.toSet())
            assertEquals(original.size,next.size)
            current=next
        }
        assertEquals(listOf("a"),MusicFolderRules.shuffledOrder(listOf("a")))
        assertTrue(MusicFolderRules.shuffledOrder(emptyList()).isEmpty())
    }

    @Test fun folderOrderSurvivesRefreshAndRestoresOriginal() {
        val original=listOf("a","b","c")
        val chosen=listOf("c","a","b")
        assertEquals(chosen,MusicFolderRules.visibleOrder(original,chosen))
        assertEquals(listOf("c","a","d"),MusicFolderRules.visibleOrder(listOf("a","c","d"),chosen))
        assertEquals(original,MusicFolderRules.visibleOrder(original,null))
        assertEquals(emptyList<String>(),MusicFolderRules.visibleOrder(emptyList(),chosen))
    }

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
