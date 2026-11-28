package com.domenota.medialoader.data.tasks

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.data.download.DownloadNetworkWait

internal object DownloadTaskFeed {
    fun eligible(row: DownloadEntity): Boolean = !row.hidden && !row.isPrivate &&
        row.derivedFromId == null && !row.sourceUrl.isNullOrBlank()

    fun snapshot(row: DownloadEntity): TaskSnapshot {
        val wait = when (DownloadNetworkWait.from(row.state, row.errorMessage)) {
            DownloadNetworkWait.WIFI -> TaskWait.WIFI
            DownloadNetworkWait.INTERNET -> TaskWait.NETWORK
            null -> null
        }
        val status = when (row.state) {
            DownloadState.QUEUED -> if (wait == null) TaskStatus.QUEUED else TaskStatus.WAITING
            DownloadState.RUNNING -> TaskStatus.RUNNING
            DownloadState.COMPLETED -> TaskStatus.COMPLETE
            DownloadState.FAILED -> TaskStatus.FAILED
            DownloadState.CANCELLED -> TaskStatus.CANCELLED
        }
        val done = row.bytesDownloaded.coerceAtLeast(0)
        val total = row.sizeBytes?.takeIf { it > 0 && it >= done }
        val commands = when (status) {
            TaskStatus.RUNNING, TaskStatus.QUEUED, TaskStatus.WAITING -> setOf(TaskCommand.CANCEL)
            TaskStatus.FAILED -> setOf(TaskCommand.RETRY)
            else -> emptySet()
        }
        return TaskSnapshot(taskId(TaskOwner.DOWNLOADS, "file:${row.id}"), TaskKind.DOWNLOAD, status,
            progress = TaskProgress(done, total), errors = if (status == TaskStatus.FAILED) 1 else 0,
            wait = wait, commands = commands, updatedAt = row.createdAtEpochMillis.coerceAtLeast(0),
            source=TaskSource.from(row.providerId), media=TaskMedia.valueOf(row.mediaType.name))
    }
    fun groups(rows: List<DownloadEntity>): List<TaskSnapshot> =
        rows.filter(::eligible).groupBy { if (it.groupId != null) "group:${it.groupId}" else "file:${it.id}" }
            .map { (key, group) ->
                val children = group.map(::snapshot)
                if (!key.startsWith("group:")) children.single() else aggregate(key, children)
            }
    private fun aggregate(key: String, children: List<TaskSnapshot>): TaskSnapshot {
        val complete = children.count { it.status == TaskStatus.COMPLETE }
        val failures = children.sumOf { it.errors }
        val status = when {
            children.any { it.status == TaskStatus.RUNNING } -> TaskStatus.RUNNING
            children.any { it.status == TaskStatus.QUEUED } -> TaskStatus.QUEUED
            children.any { it.status == TaskStatus.WAITING } -> TaskStatus.WAITING
            complete == children.size -> TaskStatus.COMPLETE
            complete > 0 -> TaskStatus.PARTIAL
            failures > 0 -> TaskStatus.FAILED
            else -> TaskStatus.CANCELLED
        }
        val commands = buildSet {
            if (children.any { TaskCommand.CANCEL in it.commands }) add(TaskCommand.CANCEL)
            if (status.terminal && failures > 0) add(TaskCommand.RETRY)
        }
        return TaskSnapshot(taskId(TaskOwner.DOWNLOADS, key), TaskKind.DOWNLOAD, status,
            files = TaskProgress(complete.toLong(), children.size.toLong()), errors = failures,
            wait = if (status == TaskStatus.WAITING) children.filter { it.status == TaskStatus.WAITING }.mapNotNull { it.wait }.distinct().singleOrNull() else null,
            commands = commands, children = children, updatedAt = children.maxOf { it.updatedAt },
            source=children.map { it.source }.distinct().singleOrNull(), media=children.map { it.media }.distinct().singleOrNull())
    }
    fun commandTargets(rows: List<DownloadEntity>, id: String, command: TaskCommand): List<String> {
        val eligible = rows.filter(::eligible)
        val direct = eligible.filter { taskId(TaskOwner.DOWNLOADS, "file:${it.id}") == id }
        val group = if (direct.isNotEmpty()) direct else eligible.filter {
            it.groupId?.let { group -> taskId(TaskOwner.DOWNLOADS, "group:$group") == id } == true
        }
        return group.filter { command in snapshot(it).commands }.map { it.id }
    }
}
