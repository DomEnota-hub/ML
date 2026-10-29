package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class VkFavoriteStoreTest {
    @Test fun vkDomainsShareOneFavoriteKey() {
        assertEquals(VkFavoriteStore.key("https://vk.ru/schrodinger_humor"),
            VkFavoriteStore.key("https://vk.com/schrodinger_humor"))
        assertEquals("https://vk.com/schrodinger_humor",
            VkFavoriteStore.canonicalLink("https://vk.ru/schrodinger_humor"))
        assertNull(VkFavoriteStore.key("https://example.com/schrodinger_humor"))
    }

    @Test fun favoriteCodecDoesNotContainCredentialsAndHandlesBadInput() {
        val favorite = VkFavorite("domain:schrodinger_humor", "Юмор",
            "https://vk.com/schrodinger_humor", "https://sun1.userapi.com/avatar.jpg")
        assertEquals(listOf(favorite), VkFavoriteStore.decode(VkFavoriteStore.encode(listOf(favorite))))
        assertEquals(null, VkFavoriteStore.decode("""[{"link":"https://vk.com/schrodinger_humor","label":"Моё название"}]""")
            .single().avatarUrl)
        assertTrue(VkFavoriteStore.decode("broken").isEmpty())
    }

    @Test fun enrichmentKeepsManualNamesAndReplacesOnlyAddressFallbacks() {
        val manual = VkFavorite("domain:schrodinger_humor", "Юмор Шрёдингера",
            "https://vk.com/schrodinger_humor")
        val fallback = VkFavorite("domain:other_group", "other_group",
            "https://vk.com/other_group")
        val info = VkCommunityInfo("Официальное название",
            "https://sun1.userapi.com/avatar.jpg")
        val updated = VkFavoriteStore.mergeMetadata(listOf(manual, fallback),
            mapOf(manual.key to info, fallback.key to info))
        assertEquals("Юмор Шрёдингера", updated[0].label)
        assertEquals("Официальное название", updated[1].label)
        assertEquals(info.avatarUrl, updated[0].avatarUrl)
    }

    @Test fun legacyFavoriteGetsTodayAndSavedStateSurvivesCodecAndEnrichment() {
        val today = LocalDate.of(2026, 10, 23)
        val legacy = """[{"link":"https://vk.com/schrodinger_humor","label":"Юмор"}]"""
        val migrated = VkFavoriteStore.decode(legacy, today).single()
        assertEquals(today.toString(), migrated.sinceDate)
        assertNull(migrated.reviewedThroughEpoch)
        val reviewed = migrated.copy(reviewedThroughEpoch = 1_792_700_000)
        assertEquals(reviewed, VkFavoriteStore.decode(VkFavoriteStore.encode(listOf(reviewed)), today).single())
        assertEquals(reviewed.reviewedThroughEpoch,
            VkFavoriteStore.mergeMetadata(listOf(reviewed), mapOf(reviewed.key to
                VkCommunityInfo("Новое имя", null))).single().reviewedThroughEpoch)
    }

    @Test fun changingStartDateResetsCheckpointButMetadataDoesNot() {
        val favorite = VkFavorite("domain:example", "Example", "https://vk.com/example",
            reviewedThroughEpoch = 1_792_700_000)
        val changed = VkFavoriteStore.withSinceDate(favorite, LocalDate.of(2026, 10, 20))
        assertEquals("2026-10-20", changed.sinceDate)
        assertNull(changed.reviewedThroughEpoch)
        assertEquals(1_792_700_000L,
            VkFavoriteStore.markReviewed(favorite, 1_792_700_000).reviewedThroughEpoch)
        assertEquals(1_792_700_000L,
            VkFavoriteStore.markReviewed(favorite, 1_792_699_999).reviewedThroughEpoch)
    }
}
