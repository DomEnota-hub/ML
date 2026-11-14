package com.domenota.medialoader.data.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.ExifInterface
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
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
class SharedPhotoBatchRegressionTest {
    @Test fun fixedRatioKeepsReferenceZoomAndFreeCropKeepsFractions() {
        val crop=CropFractions(.25f,.25f,.1f,.1f)
        assertEquals(crop,PhotoBatchCrop.adapt(crop,CropAspectPreset.FREE,400,200,200,400))
        val first=VideoCropMath.centerCrop(400,200,CropAspectPreset.RATIO_1_1)
        val second=PhotoBatchCrop.adapt(first,CropAspectPreset.RATIO_1_1,400,200,200,400)
        assertEquals(1f,second.keptWidth*200/(second.keptHeight*400),.0001f)
        assertEquals(1f,first.keptHeight,0f)
        assertEquals(1f,second.keptWidth,0f)
        assertEquals(first,PhotoBatchCrop.adapt(first,CropAspectPreset.RATIO_1_1,400,200,400,200))
    }
    @Test fun fullResolutionBatchUsesOrientedCropColorDrawingAndPreservesOriginal()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val input=File.createTempFile("shared-batch-",".jpg",context.cacheDir)
        val source=Bitmap.createBitmap(640,320,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.rgb(40,60,80))}
        input.outputStream().use{source.compress(Bitmap.CompressFormat.JPEG,100,it)}
        source.recycle()
        ExifInterface(input.absolutePath).apply {setAttribute(ExifInterface.TAG_ORIENTATION,"6");saveAttributes()}
        val before=input.readBytes()
        val spec=BatchPhotoSpec(maxSide=0,format=BatchPhotoFormat.PNG,quality=95,
            editSpec=PhotoEditSpec(cropLeft=.25f,cropRight=.25f,
                rotationDegrees=90,colorAdjustments=PhotoColorAdjustments(brightness=10f),
                drawing=listOf(PhotoMark(PhotoMarkKind.LINE,listOf(PhotoPoint(.2f,.5f),PhotoPoint(.8f,.5f)),Color.RED,.03f))),
            cropPreset=CropAspectPreset.RATIO_1_1,cropReferenceWidth=640,cropReferenceHeight=320)
        val exported=BatchPhotoEditor(context).export(Uri.fromFile(input),spec)
        try {
            val result=BitmapFactory.decodeFile(exported.file.absolutePath)
            try {
                assertEquals(320,result.width);assertEquals(320,result.height)
                assertEquals(Color.RED,result.getPixel(160,160))
                assertArrayEquals(before,input.readBytes())
            }finally{result.recycle()}
        }finally{exported.file.delete();input.delete()}
    }
    @Test fun batchRejectsAiBeforeReadingSource()=runBlocking {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val result=runCatching{BatchPhotoEditor(context).export(Uri.parse("file:///missing"),
            BatchPhotoSpec(maxSide=0,editSpec=PhotoEditSpec(inpaintOverlayPath="not-allowed")))}
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }
}
