package com.domenota.medialoader.data.library

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import org.junit.Assert.*
import org.junit.Test

class VisualDuplicateCatalogTest {
    private fun row(i:Int,type:MediaType=MediaType.PHOTO)=DownloadEntity("$i","device","$i.jpg",type,DownloadState.COMPLETED,"content://media/$i",100L,100L)
    private fun ref(row:DownloadEntity)=LibraryMediaRef(row.id,setOf(row.id),row,null)
    @Test fun completePhoneCatalogIncludesElevenThousandAndAppScopeDoesNotShrinkReports(){
        val full=(1..11176).map{row(it,if(it%3==0)MediaType.VIDEO else MediaType.PHOTO)}
        val refs=full.map(::ref)+listOf(ref(row(12000).copy(hidden=true)),ref(row(12001).copy(isPrivate=true)),ref(row(12002,MediaType.AUDIO)),ref(row(12003).copy(state=DownloadState.FAILED)),ref(row(12004).copy(savedUri=null)),ref(row(12005).copy(savedUri=full[0].savedUri)))
        val app=full.take(416)
        val phone=VisualDuplicateCatalog.build(refs,app,LibrarySource.PHONE)
        val owned=VisualDuplicateCatalog.build(refs,app,LibrarySource.APP)
        assertEquals(11176,phone.scan.size);assertEquals(11176,owned.all.size);assertEquals(416,owned.scan.size)
        assertEquals(phone.all,owned.all);assertTrue(phone.scan.any{it.item.mediaType==MediaType.VIDEO})
    }
    @Test fun cachedAndFailedFilesCountInTheSameCoverage(){
        val complete=VisualScanCoverage(LibrarySource.PHONE,4).record(VisualScanOutcome.CACHED).record(VisualScanOutcome.COMPUTED,true).record(VisualScanOutcome.ALIAS).record(VisualScanOutcome.FAILED)
        assertEquals(4,complete.checked);assertEquals(1,complete.cached);assertEquals(1,complete.computed)
        assertEquals(1,complete.aliases);assertEquals(1,complete.failed);assertEquals(1,complete.incompleteVisual)
        assertTrue(runCatching{complete.record(VisualScanOutcome.CACHED)}.isFailure)
    }
}
