package com.domenota.medialoader.data.download

import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.*
import com.domenota.medialoader.data.bulk.BulkIdentity
import org.junit.Assert.*
import org.junit.Test

class MissingFileRecoveryPolicyTest {
    private val item = MediaItem("component-2", "instagram", MediaType.PHOTO, "photo.jpg",
        "https://scontent.example/new", sourceGroupId = "post", position = 2,
        sourcePageUrl = "https://www.instagram.com/p/ABCdef123/")
    private val row = DownloadEntity("stable", "instagram", "old.jpg", MediaType.PHOTO,
        DownloadState.COMPLETED, "content://media/old", 10, 1234,
        bulkMediaKey = BulkIdentity.key(item), groupId = "original-group")

    @Test fun matchesOnlyConfirmedMissingComponentRegardlessOfOrderNameOrNewCdnUrl() {
        val sibling = item.copy(id = "component-1", position = 1)
        assertEquals(item, MissingFileRecoveryPolicy.match(row, listOf(sibling, item)))
        assertEquals(item.copy(downloadUrl = "https://new.cdn/example", originalName = "renamed.jpg"),
            MissingFileRecoveryPolicy.match(row, listOf(item.copy(downloadUrl = "https://new.cdn/example", originalName = "renamed.jpg"))))
        assertNull(MissingFileRecoveryPolicy.match(row, listOf(sibling)))
    }

    @Test fun duplicateIdentityAndPrivateHiddenOrActiveTargetsAreRejected() {
        assertNull(MissingFileRecoveryPolicy.match(row, listOf(item, item)))
        listOf(row.copy(isPrivate = true), row.copy(hidden = true), row.copy(state = DownloadState.RUNNING)).forEach {
            assertNull(MissingFileRecoveryPolicy.match(it, listOf(item)))
        }
        assertNull(MissingFileRecoveryPolicy.match(row, listOf(item.copy(type = MediaType.VIDEO))))
    }

    @Test fun legacyRowsNeedSingleVerifiedPublicationAndCannotGuessCarouselPosition() {
        val legacy = row.copy(bulkMediaKey = null, groupId = null, sourcePageUrl = item.sourcePageUrl)
        val single=item.copy(id="ABCdef123",sourceGroupId="ABCdef123",position=null)
        assertEquals(single,MissingFileRecoveryPolicy.match(legacy,listOf(single)))
        assertNull(MissingFileRecoveryPolicy.match(legacy,listOf(item)))
        assertNull(MissingFileRecoveryPolicy.match(legacy, listOf(item, item.copy(id = "another"))))
        assertNull(MissingFileRecoveryPolicy.match(legacy.copy(groupId = "old-carousel"), listOf(item)))
        assertNull(MissingFileRecoveryPolicy.match(legacy, listOf(item.copy(sourcePageUrl = "https://www.instagram.com/p/Other123/"))))
        assertNull(MissingFileRecoveryPolicy.match(legacy.copy(sourcePageUrl = null, sourceUrl = null), listOf(item)))
    }

    @Test fun legacyYoutubeUsesVerifiedVideoIdentityAcrossUrlFormsAndKeepsFormat() {
        val youtube = item.copy(providerId = "youtube", type = MediaType.VIDEO,
            sourcePageUrl = "https://youtu.be/abcdefghijk", formatSelector = "format-1")
        val previous = row.copy(providerId = "youtube", mediaType = MediaType.VIDEO, bulkMediaKey = null,
            groupId = null, sourcePageUrl = "https://www.youtube.com/watch?v=abcdefghijk&list=list",
            formatSelector = "format-1")
        assertEquals(youtube, MissingFileRecoveryPolicy.match(previous, listOf(youtube)))
        assertNull(MissingFileRecoveryPolicy.match(previous, listOf(youtube.copy(formatSelector = "format-2"))))
    }

    @Test fun singleReelWithExtractedAudioDoesNotBecomeAmbiguous(){
        val url="https://www.instagram.com/reel/DfJq2fdoCdM/"
        val reel=item.copy(id="DfJq2fdoCdM",sourceGroupId="DfJq2fdoCdM",position=null,type=MediaType.VIDEO,sourcePageUrl=url)
        val audio=com.domenota.medialoader.core.provider.DefaultMediaResolver.audioTrackOf(reel).copy(sourcePageUrl=url)
        val legacy=row.copy(mediaType=MediaType.VIDEO,bulkMediaKey=null,groupId=null,sourcePageUrl=url)
        assertEquals(reel,MissingFileRecoveryPolicy.match(legacy,listOf(audio,reel)))
        assertNull(MissingFileRecoveryPolicy.match(legacy,listOf(reel.copy(position=1),audio)))
        assertNull(MissingFileRecoveryPolicy.match(legacy,listOf(reel,reel.copy(id="sibling",position=2))))
        assertNull(MissingFileRecoveryPolicy.match(legacy.copy(mediaType=MediaType.AUDIO),listOf(reel,audio)))
        assertEquals(audio,MissingFileRecoveryPolicy.match(legacy.copy(mediaType=MediaType.AUDIO,sourceUrl=reel.downloadUrl),listOf(reel,audio)))
    }
    @Test fun uniqueYoutubeFormatCanBeSelectedWithoutGuessing(){
        val url="https://youtu.be/abcdefghijk"
        val a=item.copy(providerId="youtube",type=MediaType.VIDEO,sourcePageUrl=url,formatSelector="137")
        val b=a.copy(id="other",formatSelector="136")
        val previous=row.copy(providerId="youtube",mediaType=MediaType.VIDEO,bulkMediaKey=null,groupId=null,sourcePageUrl=url,formatSelector="137")
        assertEquals(a,MissingFileRecoveryPolicy.match(previous,listOf(a,b)))
        assertNull(MissingFileRecoveryPolicy.match(previous,listOf(b)))
        assertNull(MissingFileRecoveryPolicy.match(previous,listOf(a,a.copy(id="duplicate"))))
    }
    @Test fun legacyPhotoCannotBecomeVideoOrAnUnverifiedCover(){
        val url="https://www.instagram.com/reel/DfJq2fdoCdM/"
        val previous=row.copy(bulkMediaKey=null,groupId=null,sourcePageUrl=url)
        val video=item.copy(type=MediaType.VIDEO,id="DfJq2fdoCdM",sourceGroupId="DfJq2fdoCdM",
            position=null,sourcePageUrl=url,previewUrl="https://cdn.example/cover.jpg")
        val audio=video.copy(type=MediaType.AUDIO,id="${video.id}_audio")
        assertNull(MissingFileRecoveryPolicy.match(previous,listOf(video,audio)))
        assertTrue(MissingFileRecoveryPolicy.mismatchMessage(previous,listOf(video,audio)).contains("Подтверждённая фотография не найдена"))
        assertNull(MissingFileRecoveryPolicy.match(previous,listOf(video,video.copy(type=MediaType.PHOTO,id="cover"))))
    }
    @Test fun recoveryOnlyRetriesRecognizedNetworkFailures(){
        assertTrue(RecoveryTransferPolicy.networkInterrupted(java.net.ProtocolException("unexpected end of stream")))
        assertTrue(RecoveryTransferPolicy.networkInterrupted(java.net.SocketTimeoutException()))
        assertFalse(RecoveryTransferPolicy.networkInterrupted(java.io.IOException("disk full")))
        assertFalse(RecoveryTransferPolicy.networkInterrupted(javax.net.ssl.SSLHandshakeException("certificate")))
    }
}
