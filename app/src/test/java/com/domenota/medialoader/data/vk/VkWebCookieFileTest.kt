package com.domenota.medialoader.data.vk

import org.junit.Assert.assertEquals
import org.junit.Test

class VkWebCookieFileTest {
    @Test fun onlyVkCookieRowsCanReturnToTheBrowser() {
        val rows = sequenceOf(
            ".vk.com\tTRUE\t/\tTRUE\t0\tremixsid\told-session",
            ".vk.ru\tTRUE\t/\tTRUE\t0\tremixsid6\tnew-session",
            ".id.vk.ru\tTRUE\t/\tTRUE\t0\taccess_token\tvalue",
            ".vk.com.evil.test\tTRUE\t/\tTRUE\t0\tremixsid\tsteal",
            ".vk.com\tTRUE\t/\tTRUE\t0\tbad;name\tinvalid",
        )
        assertEquals(listOf(".vk.com", ".vk.ru", ".id.vk.ru"),
            VkWebCookieFile.parse(rows).map { it.domain })
    }
}
