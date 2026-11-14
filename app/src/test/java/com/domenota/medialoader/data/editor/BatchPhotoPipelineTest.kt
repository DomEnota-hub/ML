package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.ui.screens.renderBatchPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchPhotoPipelineTest {
    @Test fun everyCatalogFilterUsesThePhotoEditorPipelineAtBothIntensityLimits() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val source = Bitmap.createBitmap(intArrayOf(0xffb23d18.toInt(),0xff1998dd.toInt(),
            0xffe4c69b.toInt(),0xff2f6145.toInt()),2,2,Bitmap.Config.ARGB_8888)
        PhotoEffect.entries.forEach { effect ->
            listOf(0f,100f).forEach { strength ->
                val spec = BatchPhotoSpec(maxSide=0,format=BatchPhotoFormat.PNG,
                    effect=effect,effectStrength=strength)
                val expected = PhotoColorMath.apply(source,PhotoColorAdjustments(effect=effect,effectStrength=strength))
                val actual = renderBatchPreview(context,source,spec,640)
                assertTrue("${effect.name}:$strength",expected.sameAs(actual))
                assertFalse(source.isRecycled)
                if(actual!==source)actual.recycle()
                if(expected!==source)expected.recycle()
            }
        }
        source.recycle()
    }
    @Test fun intensityAndDimensionsAreValidated() {
        listOf(-1f,101f,Float.NaN).forEach { value ->
            assertThrows(IllegalArgumentException::class.java){BatchPhotoSpec(effectStrength=value)}
        }
        assertEquals(100f,BatchPhotoSpec().effectStrength)
        assertEquals(1920 to 1080,BatchPhotoSpec().dimensions(3840,2160))
    }
}
