package com.domenota.medialoader.ui.screens.music

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class RecentMusicPolicyTest {
    private val zone=ZoneId.of("Europe/Moscow")
    private fun track(id:String,day:Int)=DownloadEntity(id,"device","$id.mp3",MediaType.AUDIO,DownloadState.COMPLETED,
        "content://media/external/audio/media/$id",100L,LocalDate.of(2026,11,day).atStartOfDay(zone).toInstant().toEpochMilli())
    @Test fun allLargeDayTracksAndSparseDatesArePreserved(){
        val rows=(1..10000).map{track("$it",15)}+track("older",3)
        val days=RecentMusicPolicy.days(rows,zone)
        assertEquals(listOf(LocalDate.of(2026,11,15),LocalDate.of(2026,11,3)),days.map{it.date})
        assertEquals(10000,days.first().tracks.size)
        assertEquals(10001,days.sumOf{it.tracks.size})
        assertEquals(days,RecentMusicPolicy.days(rows.reversed(),zone))
    }
    @Test fun emptyPrivateMissingAndRepeatedTracksAreExcluded(){
        val a=track("a",15)
        val days=RecentMusicPolicy.days(listOf(a,a,track("b",12).copy(isPrivate=true),track("c",9).copy(hidden=true),track("d",8).copy(savedUri=null)),zone)
        assertEquals(listOf(a),days.single().tracks)
        assertEquals(a.createdAtEpochMillis,RecentMusicPolicy.days(listOf(a.copy(originalName="renamed",recognizedTitle="new")),zone).single().tracks.single().createdAtEpochMillis)
        assertTrue(RecentMusicPolicy.days(emptyList(),zone).isEmpty())
        assertNull(RecentMusicPolicy.selectedDay(emptyList(),null))
        assertNull(RecentMusicPolicy.days(listOf(a.copy(createdAtEpochMillis=0)),zone).single().date)
    }
    @Test fun calendarKeepsWeekAlignmentAndSelectsOnlyNonemptyDates(){
        val month=YearMonth.of(2026,11)
        val cells=RecentMusicPolicy.calendarCells(month)
        assertEquals(6,cells.indexOf(month.atDay(1)))
        assertEquals(30,cells.count{it!=null});assertEquals(0,cells.size%7)
        val days=RecentMusicPolicy.days(listOf(track("a",15),track("b",3)),zone)
        assertEquals(month.atDay(15),RecentMusicPolicy.selectedDay(days,month.atDay(12)))
        assertEquals(month.atDay(3),RecentMusicPolicy.selectedDay(days,month.atDay(3)))
    }
    @Test fun russianTrackCounts(){
        listOf(0 to "0 треков",1 to "1 трек",2 to "2 трека",11 to "11 треков",14 to "14 треков",51 to "51 трек",163 to "163 трека").forEach{(n,label)->assertEquals(label,RussianCounts.tracks(n))}
    }
    @Test fun duplicateActionUsesSavedModeOnlyForResume(){
        assertEquals("Продолжить быстрый поиск",musicScanActionLabel(false,true,false))
        assertEquals("Запустить расширенный поиск",musicScanActionLabel(true,true,false))
        assertEquals("Продолжить расширенный поиск",musicScanActionLabel(true,true,true))
        assertEquals("Запустить быстрый поиск",musicScanActionLabel(false,false,false))
    }
}
