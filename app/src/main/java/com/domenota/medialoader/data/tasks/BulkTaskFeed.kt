package com.domenota.medialoader.data.tasks

import com.domenota.medialoader.data.bulk.BulkPhase
import com.domenota.medialoader.data.bulk.BulkState
import com.domenota.medialoader.core.database.DownloadEntity

internal object BulkTaskFeed {
    fun belongs(row:DownloadEntity,job:BulkState):Boolean =
        job.jobId.isNotEmpty() && row.groupId?.startsWith(job.jobId+":")==true
    fun snapshot(job:BulkState,rows:List<DownloadEntity>):TaskSnapshot? {
        if(job.phase==BulkPhase.EMPTY || job.jobId.isEmpty())return null
        val children=rows.filter{belongs(it,job)&&DownloadTaskFeed.eligible(it)}.map(DownloadTaskFeed::snapshot)
        val complete=children.count{it.status==TaskStatus.COMPLETE}
        val failed=children.sumOf{it.errors}+job.errors.size
        val analysis=job.phase in setOf(BulkPhase.ANALYZING,BulkPhase.PREVIEW)
        val status=when(job.phase) {
            BulkPhase.EMPTY -> return null
            BulkPhase.ANALYZING -> TaskStatus.RUNNING
            BulkPhase.PREVIEW -> TaskStatus.WAITING
            BulkPhase.PAUSED -> TaskStatus.PAUSED
            BulkPhase.STOPPED -> TaskStatus.CANCELLED
            BulkPhase.QUEUING -> when {
                children.any{it.active} || job.selectedCount>job.queuedCount -> TaskStatus.RUNNING
                children.any{it.status==TaskStatus.QUEUED} -> TaskStatus.QUEUED
                children.any{it.status==TaskStatus.WAITING} -> TaskStatus.WAITING
                else -> TaskStatus.RUNNING
            }
            BulkPhase.FINISHED -> when {
                children.any{it.active} -> TaskStatus.RUNNING
                children.any{it.status==TaskStatus.QUEUED} -> TaskStatus.QUEUED
                children.any{it.status==TaskStatus.WAITING} -> TaskStatus.WAITING
                failed>0 && complete==0 -> TaskStatus.FAILED
                failed>0 || complete<job.plannedCount -> TaskStatus.PARTIAL
                else -> TaskStatus.COMPLETE
            }
        }
        val commands=when(job.phase) {
            BulkPhase.ANALYZING -> setOf(TaskCommand.CANCEL)
            BulkPhase.QUEUING -> setOf(TaskCommand.PAUSE,TaskCommand.CANCEL)
            BulkPhase.PAUSED -> setOf(TaskCommand.RESUME,TaskCommand.CANCEL)
            BulkPhase.FINISHED -> if(status.terminal&&children.any{TaskCommand.RETRY in it.commands})setOf(TaskCommand.RETRY) else emptySet()
            else -> emptySet()
        }
        return TaskSnapshot(taskId(TaskOwner.BULK,job.jobId),TaskKind.BULK,status,
            files=if(analysis) countProgress(job.cursor,job.urls.size) else countProgress(complete,job.plannedCount),
            errors=failed,wait=if(status==TaskStatus.WAITING) if(job.phase==BulkPhase.PREVIEW)TaskWait.USER_ACTION else children.mapNotNull{it.wait}.distinct().singleOrNull() else null,
            commands=commands,children=children.map{it.copy(commands=emptySet())},updatedAt=job.createdAtEpochMillis,
            stage=if(analysis)TaskStage.SCANNING else TaskStage.TRANSFER)
    }
}
