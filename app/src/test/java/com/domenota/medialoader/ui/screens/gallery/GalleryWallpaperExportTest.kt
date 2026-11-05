package com.domenota.medialoader.ui.screens.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.domenota.medialoader.core.database.DownloadEntity
import com.domenota.medialoader.core.model.DownloadState
import com.domenota.medialoader.core.model.MediaType
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class GalleryWallpaperExportTest {
    @Test fun photoSourceWithUnsupportedMimeBecomesReadableImagePng() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val raw = File(context.cacheDir, "source-with-no-extension")
        val bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.YELLOW)
        try { raw.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        val originalBytes = raw.readBytes()
        val media = DownloadEntity(
            "wallpaper-test", "device", "unknown.bin", MediaType.PHOTO,
            DownloadState.COMPLETED, Uri.fromFile(raw).toString(), raw.length(), 1_000_000
        )
        val uri = prepareGalleryWallpaperUri(context, media)
        assertEquals("content", uri.scheme)
        assertEquals("image/png", context.contentResolver.getType(uri))
        assertTrue(context.contentResolver.openInputStream(uri)?.use { it.read() != -1 } == true)
        assertTrue(originalBytes.contentEquals(raw.readBytes()))
    }
}
