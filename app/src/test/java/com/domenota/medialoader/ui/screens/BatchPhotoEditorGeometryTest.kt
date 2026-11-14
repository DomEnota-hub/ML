package com.domenota.medialoader.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.domenota.medialoader.data.editor.BatchPhotoState
import com.domenota.medialoader.data.editor.BatchPhotoSpec
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.MediaType
import com.domenota.medialoader.core.model.DownloadState
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
    @Test fun qualityChangesRetainPreviewAndSliderGeometryAndScrolling() {
        compose.setContent { MaterialTheme {
            BatchPhotoEditorScreen(emptyList(),BatchPhotoState(),false,true,{},{_,_->},{})
        } }
        val slider=compose.onNodeWithTag("batch-quality")
        slider.performScrollTo()
        val before=slider.fetchSemanticsNode().boundsInRoot
        slider.performTouchInput { click(center) }
        slider.performSemanticsAction(SemanticsActions.SetProgress){it(100f)}
        compose.waitForIdle()
        assertEquals(before,slider.fetchSemanticsNode().boundsInRoot)
        slider.performTouchInput { swipeRight(); swipeLeft() }
        compose.waitForIdle()
        assertEquals(before,slider.fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("batch-preview").performScrollTo().assertIsDisplayed()
        slider.performScrollTo().assertIsDisplayed()
    }
    @Test fun batchWatermarkEntryUsesSharedEditorAndPassesDraftToExport() {
        val file=File.createTempFile("batch-entry-",".png",compose.activity.cacheDir)
        val bitmap=Bitmap.createBitmap(240,400,Bitmap.Config.ARGB_8888)
        file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        bitmap.recycle()
        val row=DownloadEntity("photo","device","photo.png",MediaType.PHOTO,DownloadState.COMPLETED,
            Uri.fromFile(file).toString(),file.length(),1L)
        var exported:BatchPhotoSpec?=null
        try {
            compose.setContent { MaterialTheme {
                BatchPhotoEditorScreen(listOf(row),BatchPhotoState(),false,true,{},{_,spec->exported=spec},{})
            } }
            compose.onNodeWithTag("batch-edit-watermark").performScrollTo()
            compose.waitUntil(15_000){compose.onAllNodes(hasTestTag("batch-edit-watermark") and isEnabled())
                .fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("batch-edit-watermark").performClick()
            compose.onNodeWithText("Изменить").performClick()
            compose.onNodeWithTag("editor-text-input").performTextReplacement("Общий водяной знак")
            compose.onNodeWithText("Готово").performClick()
            compose.onNodeWithText("Обработать фото").performClick()
            compose.runOnIdle {
                assertNotNull(exported)
                assertEquals("Общий водяной знак",exported!!.watermarkLayers.single().text)
                assertEquals("",exported!!.watermark)
            }
        } finally {file.delete()}
    }

}
