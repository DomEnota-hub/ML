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
        assertEquals(item, MissingFileRecoveryPolicy.match(legacy, listOf(item)))
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
}
