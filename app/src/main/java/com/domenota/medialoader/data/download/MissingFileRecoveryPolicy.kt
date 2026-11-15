package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaItem
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.provider.*
import com.domenota.medialoader.data.bulk.BulkIdentity

internal class MissingRecoveryFailure(message:String):IllegalStateException(message)

/** An absent carousel component is never inferred from title, size or a list position. */
internal object MissingFileRecoveryPolicy {
    fun eligible(row: DownloadEntity) = row.state == DownloadState.COMPLETED && !row.hidden && !row.isPrivate

    fun match(row: DownloadEntity, candidates: List<MediaItem>): MediaItem? {
        if (!eligible(row)) return null
        val exact = candidates.filter { it.providerId == row.providerId && it.type == row.mediaType &&
            row.bulkMediaKey != null && BulkIdentity.key(it) == row.bulkMediaKey && sameKnownPublication(row,it) }
        if (row.bulkMediaKey != null) return exact.singleOrNull()
        // A single Reel can resolve to video + extracted audio. Neither that
        // synthetic choice nor alternative encodings constitute a carousel.
        if(row.groupId!=null)return null
        val original=publication(row.sourcePageUrl?:row.sourceUrl?:return null)?:return null
        val item=candidates.filter{it.providerId==row.providerId&&it.type==row.mediaType&&
            publication(it.sourcePageUrl?:it.downloadUrl)==original&&it.formatSelector==row.formatSelector}.singleOrNull()?:return null
        if(row.providerId=="instagram"){
            val link=InstagramLinkParser.parse(row.sourcePageUrl?:row.sourceUrl?:return null)?:return null
            val root=candidates.filter{it.providerId=="instagram"&&it.type!=MediaType.AUDIO}.singleOrNull()?:return null
            if(root.position!=null||root.id!=link.id||root.sourceGroupId!=link.id||
                publication(root.sourcePageUrl?:root.downloadUrl)!=original)return null
            if(item.type==MediaType.AUDIO){
                // Do not confuse a publication-level music asset with extracted audio.
                if(item.id!="${root.id}_audio"||root.type!=MediaType.VIDEO||row.sourceUrl!=root.downloadUrl)return null
            }else if(item!=root)return null
        }
        return item
    }

    /** Re-resolve only the same original component/encoding, never a sibling. */
    fun sameCandidate(old:MediaItem,new:MediaItem):Boolean =
        old.providerId==new.providerId&&old.type==new.type&&old.id==new.id&&
            old.sourceGroupId==new.sourceGroupId&&old.position==new.position&&
            old.formatSelector==new.formatSelector&&
            publication(old.sourcePageUrl?:old.downloadUrl)!=null&&
            publication(old.sourcePageUrl?:old.downloadUrl)==publication(new.sourcePageUrl?:new.downloadUrl)

    fun mismatchMessage(row:DownloadEntity,candidates:List<MediaItem>):String {
        val legacyPhoto=row.providerId=="instagram"&&row.mediaType==MediaType.PHOTO&&
            row.bulkMediaKey==null&&candidates.any{it.providerId=="instagram"&&it.type==MediaType.VIDEO}&&
            candidates.none{it.providerId=="instagram"&&it.type==MediaType.PHOTO}
        return if(legacyPhoto)
            "Исходная запись содержит фотографию, но источник вернул видео. Подтверждённая фотография не найдена. Исходная запись сохранена."
        else "Медиа найдено, но не удалось точно определить отсутствующий файл. Исходная запись сохранена."
    }

    private fun sameKnownPublication(row:DownloadEntity,item:MediaItem):Boolean {
        val old=publication(row.sourcePageUrl?:row.sourceUrl.orEmpty())
        return old==null||old==publication(item.sourcePageUrl?:item.downloadUrl)
    }

    private fun publication(url: String): String? =
        InstagramLinkParser.parse(url)?.let { "instagram:${it.isStory}:${it.id}" }
            ?: YouTubeLinkParser.parse(url)?.let { "youtube:${it.videoId}" }
            ?: VkLinkParser.parse(url)?.canonicalUrl()
            ?: RutubeLinkParser.parse(url)?.canonicalUrl()
}
