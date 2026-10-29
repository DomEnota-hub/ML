package com.domenota.medialoader.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogNamesTest {
    @Test fun normalizesWhitespaceAndCaseInsensitiveKey() {
        assertEquals("Для монтажа", CatalogNames.normalize("  Для   монтажа  "))
        assertEquals(CatalogNames.key("ДЛЯ МОНТАЖА"), CatalogNames.key("для монтажа"))
    }

    @Test fun rejectsBlankAndOverlongNames() {
        listOf("  ", "x".repeat(49), "name\u0000bad").forEach { input ->
            try {
                CatalogNames.normalize(input)
                throw AssertionError("Expected invalid folder name: $input")
            } catch (_: IllegalArgumentException) { }
        }
    }
}
