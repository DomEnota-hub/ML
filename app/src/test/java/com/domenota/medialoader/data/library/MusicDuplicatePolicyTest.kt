package com.domenota.medialoader.data.library

import org.junit.Assert.*
import org.junit.Test

class MusicDuplicatePolicyTest {
    private fun t(id: String, title: String, artist: String?, sha: String = id, payload: String? = null,
                  filename: String = "$title.mp3", linked: Boolean = false, current: Boolean = false, duration: Long = 203000) =
        DuplicateTrack(id,title,artist,filename,"uri:$id",100,1,sha,payload,duration,192000,"Music",linked,current)
    @Test fun compositionVersionsAndArtistAliasesShareGroup() {
        val rows = listOf(t("a","Кукла колдуна","Король и Шут"), t("b","Кукла колдуна КиШ","КиШ"),
            t("c","Кукла колдуна (Live)","Король и Шут",duration=252000), t("d","Кукла колдуна (Slowed)","КиШ",duration=261000),
            t("e","Кукла колдуна (Cover)","Другой исполнитель"), t("f","Кукла колдуна Speed Up","КиШ"),
            t("g","Кукла колдуна (Remix)","Король и Шут"), t("h","Король и Шут - Кукла колдуна",null),
            t("i","Кукла колдуна (1)","Король и Шут"))
        val g = MusicDuplicatePolicy.groups(rows).single()
        assertEquals(rows.map { it.id }.toSet(),g.tracks.map { it.id }.toSet())
        assertEquals("Разные версии композиции",g.reason)
    }
    @Test fun sameNameAloneDoesNotProveDifferentArtistsAreOneSong() {
        assertTrue(MusicDuplicatePolicy.groups(listOf(t("a","Same","A"),t("b","Same","B"))).isEmpty())
        assertEquals("alive",MusicDuplicatePolicy.baseTitle(t("a","Alive","A")))
        assertNotEquals(MusicDuplicatePolicy.baseTitle(t("a","Part 1","A")),MusicDuplicatePolicy.baseTitle(t("b","Part 2","A")))
    }
    @Test fun strongContentMatchesDifferentNamesAndTags() {
        assertEquals(2,MusicDuplicatePolicy.groups(listOf(t("a","One","A",sha="same"),t("b","Two","B",sha="same"))).single().tracks.size)
        assertEquals(2,MusicDuplicatePolicy.groups(listOf(t("a","One","A",payload="same-encoded"),t("b","Two","B",payload="same-encoded"))).single().tracks.size)
    }
    @Test fun selectAllRetainsOnePerGroupAndCanChangeKeeper() {
        val groups = MusicDuplicatePolicy.groups(listOf(t("a","Song","A",linked=true),t("b","Song (Live)","A"),t("c","Song (Cover)","B"),t("d","Other","A"),t("e","Other","A")))
        assertEquals(2,groups.size)
        val g = groups.first { it.tracks.size == 3 }
        assertEquals("a",MusicDuplicatePolicy.recommended(g))
        val selected = MusicDuplicatePolicy.selectAll(groups,mapOf(g.key to "b"))
        assertEquals(3,selected.size); assertFalse("b" in selected); assertTrue(MusicDuplicatePolicy.validSelection(groups,selected))
        assertFalse(MusicDuplicatePolicy.validSelection(groups,g.tracks.map{it.id}.toSet()))
        assertTrue(MusicDuplicatePolicy.validSelection(groups,selected-"a"))
    }
    @Test fun unknownArtistDoesNotBridgeDifferentKnownArtists() {
        val groups = MusicDuplicatePolicy.groups(listOf(t("a","Same","A"),t("b","Same",null),t("c","Same","C")))
        assertFalse(groups.any { it.tracks.size == 3 })
        assertEquals(groups.flatMap { it.tracks }.size,groups.flatMap { it.tracks }.map { it.id }.distinct().size)
    }
}
