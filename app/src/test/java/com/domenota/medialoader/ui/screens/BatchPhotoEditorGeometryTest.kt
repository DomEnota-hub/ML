package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.editor.*
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w390dp-h800dp-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchPhotoEditorGeometryTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun photo(name:String,width:Int,height:Int):Pair<File,DownloadEntity> {
        val file=File.createTempFile("batch-entry-",".png",compose.activity.cacheDir)
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        return file to DownloadEntity(name,"device","$name.png",MediaType.PHOTO,DownloadState.COMPLETED,
            Uri.fromFile(file).toString(),file.length(),1L)
    }
    private fun waitForPhoto() {
        compose.waitUntil(15_000){compose.onAllNodes(hasTestTag("batch-export") and isEnabled())
            .fetchSemanticsNodes().isNotEmpty()}
    }
    @Test fun sharedToolsCropAndSettingsSurviveSwitchWithoutQualityOrResize() {
        val (first,a)=photo("first",240,400)
        val (second,b)=photo("second",400,240)
        var exported:BatchPhotoSpec?=null
        try {
            compose.setContent { MaterialTheme {
                BatchPhotoEditorScreen(listOf(a,b),BatchPhotoState(),false,true,{},{_,spec->exported=spec},{})
            } }
            waitForPhoto()
            compose.onNodeWithText("AI").assertDoesNotExist()
            compose.onNodeWithTag("batch-quality").assertDoesNotExist()
            compose.onNodeWithText("Максимальная сторона").assertDoesNotExist()
            compose.onNodeWithTag("batch-preview").assertIsDisplayed()
            compose.onNodeWithText("Соотношение").performClick()
            compose.onNodeWithText("1:1").performScrollTo().performClick()
            compose.onNodeWithTag("batch-photo-1").performClick()
            waitForPhoto()
            compose.onNodeWithText("1:1").assertIsSelected()
            compose.onNodeWithText("2 / 2").assertIsDisplayed()
            compose.onNodeWithTag("batch-export").performClick()
            compose.onNodeWithText("Максимальная сторона").assertDoesNotExist()
            compose.onNodeWithTag("batch-quality").assertDoesNotExist()
            compose.onNodeWithText("PNG").performClick()
            compose.onNodeWithTag("batch-confirm-export").performClick()
            compose.runOnIdle {
                val spec=requireNotNull(exported)
                assertEquals(0,spec.maxSide)
                assertEquals(95,spec.quality)
                assertEquals(BatchPhotoFormat.PNG,spec.format)
                assertEquals(CropAspectPreset.RATIO_1_1,spec.cropPreset)
                assertEquals(240,spec.cropReferenceWidth)
                assertEquals(400,spec.cropReferenceHeight)
                val adapted=spec.cropFor(400,240)
                assertEquals(1f,adapted.keptWidth*400/(adapted.keptHeight*240),.002f)
            }
        } finally {first.delete();second.delete()}
    }
    @Test fun batchTextUsesTheActualSharedEditorAndPassesLayersToExport() {
        val (file,row)=photo("photo",240,400)
        var exported:BatchPhotoSpec?=null
        try {
            compose.setContent { MaterialTheme {
                BatchPhotoEditorScreen(listOf(row),BatchPhotoState(),false,true,{},{_,spec->exported=spec},{})
            } }
            waitForPhoto()
            compose.onNodeWithText("Водяной знак").performScrollTo().performClick()
            compose.onNodeWithText("Текстовый знак").performClick()
            compose.onNodeWithTag("editor-text-add").performClick()
            compose.onNodeWithTag("editor-text-input").performTextReplacement("Общий водяной знак")
            compose.onNodeWithTag("editor-text-apply").performClick()
            compose.onNodeWithTag("batch-export").performClick()
            compose.onNodeWithTag("batch-confirm-export").performClick()
            compose.runOnIdle {
                assertEquals("Общий водяной знак",requireNotNull(exported?.editSpec).textLayers.single().text)
                assertNull(exported?.editSpec?.inpaintOverlayPath)
            }
        } finally {file.delete()}
    }
}
