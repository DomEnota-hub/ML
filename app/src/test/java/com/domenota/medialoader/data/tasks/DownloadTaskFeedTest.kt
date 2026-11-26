package com.domenota.medialoader.data.tasks
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.download.DownloadNetworkWait
import org.junit.Assert.*
import org.junit.Test

class DownloadTaskFeedTest {
    private fun row(id: String, state: DownloadState, group: String? = null) =
        DownloadEntity(id,"instagram","secret.jpg",MediaType.PHOTO,state,null,100,1,
            sourceUrl="https://example.com/secret",groupId=group)
    @Test fun waitingIsNotAnErrorAndNoPrivateRowsArePublished() {
        val waiting = row("1",DownloadState.QUEUED).copy(errorMessage=DownloadNetworkWait.WIFI.marker)
        val rows = DownloadTaskFeed.groups(listOf(waiting,row("2",DownloadState.RUNNING).copy(isPrivate=true)))
        assertEquals(1, rows.size)
        assertEquals(TaskStatus.WAITING,rows.single().status)
        assertEquals(TaskWait.WIFI,rows.single().wait)
        assertEquals(0,rows.single().errors)
        assertFalse(rows.toString().contains("secret"))
    }
    @Test fun groupRetryTargetsOnlyFailedChildren() {
        val rows = listOf(row("1",DownloadState.COMPLETED,"g"),row("2",DownloadState.FAILED,"g"),row("3",DownloadState.CANCELLED,"g"))
        val group = DownloadTaskFeed.groups(rows).single()
        assertEquals(TaskStatus.PARTIAL,group.status)
        assertEquals(1L,group.files!!.done)
        assertEquals(listOf("2"),DownloadTaskFeed.commandTargets(rows,group.id,TaskCommand.RETRY))
    }
    @Test fun groupActiveCountsOnlyOneTask() {
        val group = DownloadTaskFeed.groups(listOf(row("1",DownloadState.RUNNING,"g"),row("2",DownloadState.QUEUED,"g")))
        assertEquals(1,group.count { it.active })
        assertEquals(2,group.single().children.size)
    }
}
