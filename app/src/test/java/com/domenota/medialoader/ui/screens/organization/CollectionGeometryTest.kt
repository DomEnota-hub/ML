package com.domenota.medialoader.ui.screens.organization

import org.junit.Assert.*
import org.junit.Test

class CollectionGeometryTest {
    @Test fun portraitAndLandscapeCropAlwaysFillFourByFiveWithoutDistortion() {
        for ((width,height) in listOf(400 to 1000, 1600 to 900, 800 to 1000)) {
            val crop=CollectionCrop().rect(width,height)
            assertEquals(.8f,crop.width/crop.height,.001f)
            assertTrue(crop.left>=0f && crop.top>=0f)
            assertTrue(crop.left+crop.width<=width+.01f && crop.top+crop.height<=height+.01f)
        }
    }
    @Test fun panAndZoomRemainInsideSourceAndSurviveNormalizedRoundTrip() {
        val crop=CollectionCrop(99f,-12f,3f).bounded(1600,900)
        val rect=crop.rect(1600,900)
        assertTrue(rect.left>=0f && rect.top>=0f)
        assertTrue(rect.left+rect.width<=1600.01f)
        assertTrue(rect.top+rect.height<=900.01f)
        assertEquals(crop,CollectionCrop(crop.centerX,crop.centerY,crop.zoom).bounded(1600,900))
        assertTrue(rect.width<CollectionCrop().rect(1600,900).width)
    }
    @Test fun singleCollectionKeepsHalfWidthAndManyHaveSameWidth() {
        for(available in listOf(240f,360f,800f))assertEquals(available,collectionCardWidth(available)*2+8f,.001f)
        assertEquals(176f,collectionCardWidth(360f),0f)
        assertEquals(116f,collectionCardWidth(240f),0f)
    }
    @Test fun rangeSelectionPreservesOtherTabsAndNeverAddsExistingMembers() {
        val result=collectionRangeSelection(setOf("AUDIO:a"),listOf("PHOTO:a","PHOTO:b","PHOTO:c"),"PHOTO:a","PHOTO:c",setOf("PHOTO:b"))
        assertEquals(setOf("AUDIO:a","PHOTO:a","PHOTO:c"),result)
    }
}
