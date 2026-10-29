package com.domenota.medialoader.data.preview

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VkThumbnailPolicyTest {
    @Test fun acceptsOnlyHttpsUserapiHosts() {
        assertTrue(VkThumbnailPolicy.isAllowedUrl("https://sun9-69.userapi.com/image.jpg"))
        assertFalse(VkThumbnailPolicy.isAllowedUrl("http://sun9-69.userapi.com/image.jpg"))
        assertFalse(VkThumbnailPolicy.isAllowedUrl("https://userapi.com.evil.example/image.jpg"))
        assertFalse(VkThumbnailPolicy.isAllowedUrl("https://userapi.com@evil.example/image.jpg"))
    }

    @Test fun requiresImageResponseType() {
        assertTrue(VkThumbnailPolicy.isImageContentType("image/jpeg"))
        assertTrue(VkThumbnailPolicy.isImageContentType("image/webp; charset=binary"))
        assertFalse(VkThumbnailPolicy.isImageContentType("text/html"))
        assertFalse(VkThumbnailPolicy.isImageContentType("application/json"))
        assertFalse(VkThumbnailPolicy.isImageContentType(null))
    }
}
