package com.domenota.medialoader.ui.screens
import com.domenota.medialoader.ui.model.DownloadUiItem
import com.domenota.medialoader.ui.model.DownloadUiState
import org.junit.Assert.*
import org.junit.Test
class DownloadErrorGridPolicyTest {
    @Test fun failedFileUsesDedicatedRowWithoutChangingNormalFourColumnOrder() {
        fun item(i:Int,state:DownloadUiState)=DownloadUiItem(id="$i",title="$i",subtitle="Ошибка",sizeLabel=null,state=state)
        val items=(1..7).map{item(it,if(it==3)DownloadUiState.FAILED else DownloadUiState.COMPLETED)}
        val rows=downloadGridRows(items)
        assertEquals(listOf(2,1,4),rows.map{it.size})
        assertEquals(items,rows.flatten())
        assertEquals("3",rows[1].single().id)
    }
}
