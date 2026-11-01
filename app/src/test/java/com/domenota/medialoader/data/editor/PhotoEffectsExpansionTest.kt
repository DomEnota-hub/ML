package com.domenota.medialoader.data.editor

import org.junit.Assert.*
import org.junit.Test

class PhotoEffectsExpansionTest {
    @Test fun richerCatalogHasNoDuplicatedParameterProfiles() {
        val styles = PhotoEffect.entries.filter { it != PhotoEffect.NONE }
        assertTrue(styles.size >= 30)
        val vectors = styles.map {
            listOf(it.saturationMultiplier, it.warmthOffset, it.contrastOffset,
                it.fade, it.grain)
        }
        assertEquals("Each preset must change the picture differently", vectors.size,
            vectors.distinct().size)
        for (category in listOf(PhotoEffectCategory.CLASSIC, PhotoEffectCategory.FILM,
            PhotoEffectCategory.PORTRAIT, PhotoEffectCategory.CINEMA,
            PhotoEffectCategory.TEXTURE)) {
            assertTrue(PhotoEffectCatalog.forCategory(category).size >= 3)
        }
    }

    @Test fun neutralZeroPresetsPreservePhotoOriginal() {
        val zero = PhotoColorAdjustments(effect = PhotoEffect.NONE)
        assertTrue(zero.isNeutral())
        assertEquals(PhotoColorAdjustments().effect, zero.effect)
    }
}
