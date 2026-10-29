package com.domenota.medialoader.data.vkcommunity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VkCommunityLinkTest {
    @Test fun acceptsCommunityAddressAndNumericId() {
        assertEquals(VkCommunityLink.Owner(-123), VkCommunityLink.parse("https://vk.com/club123"))
        assertEquals(VkCommunityLink.Owner(-123), VkCommunityLink.parse("-123"))
        assertEquals(VkCommunityLink.Domain("my_group"), VkCommunityLink.parse("vk.com/my_group"))
        assertEquals(VkCommunityLink.Domain("schrodinger_humor"),
            VkCommunityLink.parse("https://vk.ru/schrodinger_humor"))
        assertEquals(VkCommunityLink.Owner(-123),
            VkCommunityLink.parse("https://vk.com/club123?from=share"))
    }

    @Test fun refusesForeignHostAndPostLink() {
        assertNull(VkCommunityLink.parse("https://vk.com.evil.test/club123"))
        assertNull(VkCommunityLink.parse("https://vk.ru.evil.test/club123"))
        assertNull(VkCommunityLink.parse("https://vk.com/wall-123_4"))
        assertNull(VkCommunityLink.parse("https://vk.com/club123/photos"))
    }

    @Test fun classifiesApiErrorsWithoutLeakingMessage() {
        assertEquals(VkApiFailure.Kind.BAD_TOKEN, VkApiFailure.fromCode(5).kind)
        assertEquals(VkApiFailure.Kind.PERMISSION, VkApiFailure.fromCode(15).kind)
        assertEquals(VkApiFailure.Kind.RATE_LIMIT, VkApiFailure.fromCode(6).kind)
        assertEquals(VkApiFailure.Kind.PERMISSION, VkApiFailure.fromHttp(403).kind)
        assertEquals(VkApiFailure.Kind.RATE_LIMIT, VkApiFailure.fromHttp(429).kind)
    }

    @Test fun unknownVkErrorKeepsSafeCodeAndMethodForDiagnosis() {
        val failure = VkApiFailure.fromCode(100, "wall.get")
        assertEquals(VkApiFailure.Kind.OTHER, failure.kind)
        assertEquals("api_code=100 method=wall.get", failure.diagnostic())
        assertEquals("VK вернул ошибку 100 при поиске", failure.userMessage())
    }
}
