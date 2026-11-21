package com.domenota.medialoader.ui.screens.organization

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import org.junit.Test
import org.junit.Assert.assertEquals

class CollectionPurePolicyTest {
    @Test fun countsUseRussianPluralForms(){
        val expected=mapOf(0 to "0 файлов",1 to "1 файл",2 to "2 файла",4 to "4 файла",5 to "5 файлов",11 to "11 файлов",14 to "14 файлов",21 to "21 файл",22 to "22 файла")
        expected.forEach{(n,label)->assertEquals(label,com.domenota.medialoader.core.model.RussianCounts.files(n))}
    }

    @Test fun missingFileDisplayNameOnlyRemovesRecognizedSuffix(){
        val row=DownloadEntity("x","device","live.2026.MP3",MediaType.AUDIO,DownloadState.COMPLETED,null,100,100)
        assertEquals("live.2026",missingCollectionDisplayName(row))
        assertEquals("AC/DC — T.N.T.",missingCollectionDisplayName(row.copy(originalName="AC/DC — T.N.T.")))
        assertEquals("track.bin",missingCollectionDisplayName(row.copy(originalName="track.bin")))
    }

    @Test fun collectionOrderMoveAndRussianLinkForms(){
        assertEquals(listOf("b","c","a"),moveCollectionMember(listOf("a","b","c"),"a","c"))
        assertEquals(listOf("a","b"),moveCollectionMember(listOf("a","b"),"a","missing"))
        assertEquals("112 файлов",com.domenota.medialoader.core.model.RussianCounts.files(112))
        assertEquals("21 ссылка",com.domenota.medialoader.core.model.RussianCounts.links(21))
        assertEquals("14 ссылок",com.domenota.medialoader.core.model.RussianCounts.links(14))
    }
}
