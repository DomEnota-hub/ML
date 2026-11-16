package com.domenota.medialoader.ui.navigation

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.data.library.LibraryAudioQueue
import com.domenota.medialoader.data.playback.GridMediaQueue
import com.domenota.medialoader.data.storage.SavedFileStatus
import com.domenota.medialoader.ui.model.VkDownloadGroup

internal data class ViewerQueueInventory(val audio:List<DownloadEntity> = emptyList(),
    val grid:List<DownloadEntity> = emptyList(), val photos:List<DownloadEntity> = emptyList()) {
    companion object {
        fun build(ids:List<String>,rows:List<DownloadEntity>,item:DownloadEntity,statuses:Map<String,SavedFileStatus>):ViewerQueueInventory {
            if(item.mediaType==MediaType.AUDIO)return ViewerQueueInventory(audio=LibraryAudioQueue.resolve(ids,rows,item.isPrivate,statuses))
            val grid=GridMediaQueue.resolve(ids,rows,item.isPrivate,statuses)
            if(grid.isNotEmpty()||item.mediaType!=MediaType.PHOTO)return ViewerQueueInventory(grid=grid)
            val available=rows.filter{row->row.mediaType==MediaType.PHOTO && row.state==DownloadState.COMPLETED &&
                !row.hidden && row.isPrivate==item.isPrivate && row.savedUri!=null &&
                statuses[row.id]!=SavedFileStatus.MISSING && statuses[row.id]!=SavedFileStatus.INACCESSIBLE}.distinctBy{it.savedUri}
            val groupKey=VkDownloadGroup.key(item.id,item.providerId,item.groupId)
            val grouped=available.filter{VkDownloadGroup.key(it.id,it.providerId,it.groupId)==groupKey}
                .sortedWith(compareByDescending<DownloadEntity>{it.createdAtEpochMillis}.thenByDescending{it.originalName})
            return ViewerQueueInventory(photos=if(grouped.size>1)grouped else available)
        }
    }
}
