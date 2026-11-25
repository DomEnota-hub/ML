package com.domenota.medialoader.ui.screens.organization

import android.graphics.Bitmap
import com.domenota.medialoader.data.library.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Identity fallback policy needs a Bitmap, not an Activity or Compose worker. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class LastCollectionCoverFallbackTest {
    @Test fun unverifiedMediaStoreReadCannotCreateOrSwapAnAcceptedCover() {
        val bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        try {
            val accepted=LastCollectionCoverFrame(
                LastCollectionCover("slot-a","verified-content","content://media/external/images/media/17",
                    setOf("uri:content://media/external/images/media/17"),42L),
                bitmap,
            )
            assertNull(unverifiedCoverFallback(null,"slot-a",accepted.metadata.source))
            assertSame(accepted,unverifiedCoverFallback(accepted,"slot-a",accepted.metadata.source))
            assertNull(unverifiedCoverFallback(accepted,"slot-b",accepted.metadata.source))
            assertNull(unverifiedCoverFallback(accepted,"slot-a","content://media/external/images/media/18"))
            assertEquals("verified-content",
                unverifiedCoverFallback(accepted,"slot-a",accepted.metadata.source)!!.metadata.contentToken)
        }finally{bitmap.recycle()}
    }
}
