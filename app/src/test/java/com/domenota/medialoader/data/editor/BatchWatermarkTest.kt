package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.ui.screens.decodeBatchPreview
import com.domenota.medialoader.ui.screens.renderBatchPreview
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchWatermarkTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()

    @Test fun fittedInkIncludingMultilineOutlineAndShadowRemainsInsideEveryAspectAndRotation() {
        val texts=listOf("Тест", "Первая строка\nВторая строка\nТретья", "Очень длинная надпись ".repeat(23), "Строка\n".repeat(50))
        for((width,height) in listOf(320 to 900,900 to 320,800 to 800))
            for(angle in listOf(-179f,-135f,-90f,-45f,0f,45f,90f,135f,179f))
                for(text in texts) for(alignment in TextLayerAlignment.entries) {
                    val layer=TextLayer(text=text.take(500),size=.25f,centerX=0f,centerY=1f,rotation=angle,
                        outline=.15f,shadow=true,fontId="NotoSerif-Italic",alignment=alignment)
                    val placement=TextLayerFitting.placement(context,width,height,layer)
                    assertTrue("$width/$height/$angle/${placement.bounds}",placement.bounds.left>=-.01f&&
                        placement.bounds.top>=-.01f&&placement.bounds.right<=width+.01f&&placement.bounds.bottom<=height+.01f)
                    assertTrue(placement.scale>0f&&placement.scale<=1f)
                }
    }

    @Test fun placementsScaleWithResolutionAndDraftSerializationRetainsAllSettings() {
        val layer=TextLayer(text="Многострочный\nводяной знак",size=.23f,rotation=42f,centerX=.99f,centerY=.03f,
            fontId="LiberationMono-Italic",color=Color.RED,outlineColor=Color.BLUE,outline=.1f,
            opacity=.42f,bold=true,shadow=true,alignment=TextLayerAlignment.RIGHT)
        assertEquals(listOf(layer),BatchWatermarkDraft.decode(BatchWatermarkDraft.encode(listOf(layer))))
        val small=TextLayerFitting.placement(context,400,800,layer)
        val large=TextLayerFitting.placement(context,1200,2400,layer)
        // StaticLayout rounds line heights to whole pixels; normalized geometry agrees within a pixel.
        assertEquals(small.centerX/400,large.centerX/1200,.005f)
        assertEquals(small.centerY/800,large.centerY/2400,.005f)
        assertEquals(small.scale,large.scale,.01f)
    }

    @Test fun actualExifExportEqualsSharedPreviewForMultipleLayersWithoutFramePixelsOrOriginalChanges()=runBlocking {
        val input=File.createTempFile("watermark-input-",".jpg",context.cacheDir)
        val source=Bitmap.createBitmap(320,180,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(35,50,70))}
        input.outputStream().use{source.compress(Bitmap.CompressFormat.JPEG,100,it)}
        source.recycle()
        ExifInterface(input.absolutePath).apply{setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
        val before=input.readBytes()
        val layers=listOf(TextLayer(text="Тест\nводяного знака",rotation=47f,size=.2f,centerX=0f,centerY=1f,shadow=true),
            TextLayer(text="Вторая надпись",rotation=-135f,color=Color.YELLOW,centerX=.9f,centerY=.1f))
        val spec=BatchPhotoSpec(maxSide=0,format=BatchPhotoFormat.PNG,watermarkLayers=layers)
        val decoded=decodeBatchPreview(context,Uri.fromFile(input))
        assertEquals(180,decoded.width);assertEquals(320,decoded.height)
        val preview=renderBatchPreview(context,decoded,spec,640)
        val exported=BatchPhotoEditor(context).export(Uri.fromFile(input),spec)
        try {
            val actual=BitmapFactory.decodeFile(exported.file.absolutePath)
            val expected=decoded.copy(Bitmap.Config.ARGB_8888,true)
            TextLayerRenderer.draw(context,Canvas(expected),expected.width,expected.height,layers,includeAll=true,fitInside=true)
            fun assertExactPixels(label:String,reference:Bitmap,result:Bitmap){
                fun metadata(bitmap:Bitmap)="${bitmap.width}x${bitmap.height}/${bitmap.config}/alpha=${bitmap.hasAlpha()}/density=${bitmap.density}"
                assertEquals("$label width",reference.width,result.width)
                assertEquals("$label height",reference.height,result.height)
                fun pixels(bitmap:Bitmap)=IntArray(bitmap.width*bitmap.height).also{
                    bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,bitmap.height)
                }
                val expectedPixels=pixels(reference)
                val actualPixels=pixels(result)
                val mismatch=expectedPixels.indices.firstOrNull{expectedPixels[it]!=actualPixels[it]}?:-1
                println("$label: reference=${metadata(reference)}; result=${metadata(result)}; sameAs=${reference.sameAs(result)}; firstPixelMismatch=$mismatch")
                assertArrayEquals("$label: ${metadata(reference)} vs ${metadata(result)}; first mismatch=$mismatch",
                    expectedPixels,actualPixels)
            }
            assertExactPixels("Shared preview",expected,preview)
            assertExactPixels("Export contains exactly rendered text; no frame or handles",expected,actual)
            assertArrayEquals(before,input.readBytes())
            actual.recycle();expected.recycle()
        } finally {exported.file.delete();input.delete();if(preview!==decoded)preview.recycle();decoded.recycle()}
    }

    @Test fun emptyLayerAndPlaceholderNeverReachExport()=runBlocking {
        val original=Bitmap.createBitmap(100,180,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.CYAN)}
        val result=renderBatchPreview(context,original,BatchPhotoSpec(format=BatchPhotoFormat.PNG,
            watermarkLayers=listOf(TextLayer(text=""))),640)
        assertTrue(original.sameAs(result))
        assertEquals("",BatchPhotoSpec(watermarkLayers=listOf(TextLayer())).textLayers().single().text)
        if(result!==original)result.recycle();original.recycle()
    }
}
